/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "CameraSource"
#include <utils/Log.h>

#include <OMX_Component.h>
#include <binder/IPCThreadState.h>
#include <media/stagefright/CameraSource.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <camera/Camera.h>
#include <camera/CameraParameters.h>
#include <utils/String8.h>
#include <cutils/properties.h>

namespace android {

struct CameraSourceListener : public CameraListener {
    CameraSourceListener(const sp<CameraSource> &source);

    virtual void notify(int32_t msgType, int32_t ext1, int32_t ext2);
    virtual void postData(int32_t msgType, const sp<IMemory> &dataPtr);

    virtual void postDataTimestamp(
            nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr);

protected:
    virtual ~CameraSourceListener();

private:
    wp<CameraSource> mSource;

    CameraSourceListener(const CameraSourceListener &);
    CameraSourceListener &operator=(const CameraSourceListener &);
};

CameraSourceListener::CameraSourceListener(const sp<CameraSource> &source)
    : mSource(source) {
}

CameraSourceListener::~CameraSourceListener() {
}

void CameraSourceListener::notify(int32_t msgType, int32_t ext1, int32_t ext2) {
    LOGV("notify(%d, %d, %d)", msgType, ext1, ext2);
}

void CameraSourceListener::postData(int32_t msgType, const sp<IMemory> &dataPtr) {
    LOGV("postData(%d, ptr:%p, size:%d)",
         msgType, dataPtr->pointer(), dataPtr->size());

    sp<CameraSource> source = mSource.promote();
    if (source.get() != NULL) {
        source->dataCallback(msgType, dataPtr);
    }
}

void CameraSourceListener::postDataTimestamp(
        nsecs_t timestamp, int32_t msgType, const sp<IMemory>& dataPtr) {

    sp<CameraSource> source = mSource.promote();
    if (source.get() != NULL) {
        source->dataCallbackTimestamp(timestamp/1000, msgType, dataPtr);
    }
}

static int32_t getColorFormat(const char* colorFormat) {
    if (!strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_YUV422SP)) {
       return OMX_COLOR_FormatYUV422SemiPlanar;
    }

    if (!strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_YUV420SP)) {
        return OMX_COLOR_FormatYUV420SemiPlanar;
    }

    if (!strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_YUV422I)) {
        return OMX_COLOR_FormatYCbYCr;
    }

    if (!strcmp(colorFormat, CameraParameters::PIXEL_FORMAT_RGB565)) {
       return OMX_COLOR_Format16bitRGB565;
    }

    CHECK_EQ(0, "Unknown color format");
}

// static
CameraSource *CameraSource::Create() {
    sp<Camera> camera = Camera::connect(0);

    if (camera.get() == NULL) {
        return NULL;
    }

    return new CameraSource(camera);
}

// static
CameraSource *CameraSource::CreateFromCamera(const sp<Camera> &camera) {
    if (camera.get() == NULL) {
        return NULL;
    }

    return new CameraSource(camera);
}

CameraSource::CameraSource(const sp<Camera> &camera)
    : mCamera(camera),
      mNumFramesReceived(0),
      mLastFrameTimestampUs(0),
      mStarted(false),
      mFirstFrameTimeUs(0),
      mNumFramesEncoded(0),
      mNumFramesDropped(0),
      mNumGlitches(0),
      mGlitchDurationThresholdUs(200000),
      mCollectStats(false) {

    int64_t token = IPCThreadState::self()->clearCallingIdentity();
    String8 s = mCamera->getParameters();
    IPCThreadState::self()->restoreCallingIdentity(token);

    printf("params: \"%s\"\n", s.string());

    int32_t width, height, stride, sliceHeight;
    CameraParameters params(s);
    params.getPreviewSize(&width, &height);

    // Calculate glitch duraton threshold based on frame rate
    int32_t frameRate = params.getPreviewFrameRate();
    int64_t glitchDurationUs = (1000000LL / frameRate);
    if (glitchDurationUs > mGlitchDurationThresholdUs) {
        mGlitchDurationThresholdUs = glitchDurationUs;
    }

    const char *colorFormatStr = params.get(CameraParameters::KEY_VIDEO_FRAME_FORMAT);
    CHECK(colorFormatStr != NULL);
    int32_t colorFormat = getColorFormat(colorFormatStr);

    // XXX: query camera for the stride and slice height
    // when the capability becomes available.
    stride = width;
    sliceHeight = height;

    mMeta = new MetaData;
    mMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_RAW);
    mMeta->setInt32(kKeyColorFormat, colorFormat);
    mMeta->setInt32(kKeyWidth, width);
    mMeta->setInt32(kKeyHeight, height);
    mMeta->setInt32(kKeyStride, stride);
    mMeta->setInt32(kKeySliceHeight, sliceHeight);
}

CameraSource::~CameraSource() {
    if (mStarted) {
        stop();
    }
}

void CameraSource::startCameraRecording() {
    CHECK_EQ(OK, mCamera->startRecording());
}

status_t CameraSource::start(MetaData *meta) {
    CHECK(!mStarted);

    char value[PROPERTY_VALUE_MAX];
    if (property_get("media.stagefright.record-stats", value, NULL)
        && (!strcmp(value, "1") || !strcasecmp(value, "true"))) {
        mCollectStats = true;
    }

    mStartTimeUs = 0;
    int64_t startTimeUs;
    if (meta && meta->findInt64(kKeyTime, &startTimeUs)) {
        mStartTimeUs = startTimeUs;
    }

    int64_t token = IPCThreadState::self()->clearCallingIdentity();
    mCamera->setListener(new CameraSourceListener(this));
    startCameraRecording();
    IPCThreadState::self()->restoreCallingIdentity(token);

    mStarted = true;
    return OK;
}

void CameraSource::stopCameraRecording() {
    mCamera->stopRecording();
}

status_t CameraSource::stop() {
    LOGV("stop");
    Mutex::Autolock autoLock(mLock);
    mStarted = false;
    mFrameAvailableCondition.signal();

    int64_t token = IPCThreadState::self()->clearCallingIdentity();
    mCamera->setListener(NULL);
    stopCameraRecording();
    releaseQueuedFrames();
    while (!mFramesBeingEncoded.empty()) {
        LOGI("Waiting for outstanding frames being encoded: %d",
                mFramesBeingEncoded.size());
        mFrameCompleteCondition.wait(mLock);
    }
    mCamera = NULL;
    IPCThreadState::self()->restoreCallingIdentity(token);

    if (mCollectStats) {
        LOGI("Frames received/encoded/dropped: %d/%d/%d in %lld us",
                mNumFramesReceived, mNumFramesEncoded, mNumFramesDropped,
                mLastFrameTimestampUs - mFirstFrameTimeUs);
    }

    CHECK_EQ(mNumFramesReceived, mNumFramesEncoded + mNumFramesDropped);
    return OK;
}

void CameraSource::releaseRecordingFrame(const sp<IMemory>& frame) {
    mCamera->releaseRecordingFrame(frame);
}

void CameraSource::releaseQueuedFrames() {
    List<sp<IMemory> >::iterator it;
    while (!mFramesReceived.empty()) {
        it = mFramesReceived.begin();
        releaseRecordingFrame(*it);
        mFramesReceived.erase(it);
        ++mNumFramesDropped;
    }
}

sp<MetaData> CameraSource::getFormat() {
    return mMeta;
}

void CameraSource::releaseOneRecordingFrame(const sp<IMemory>& frame) {
    int64_t token = IPCThreadState::self()->clearCallingIdentity();
    releaseRecordingFrame(frame);
    IPCThreadState::self()->restoreCallingIdentity(token);
}

void CameraSource::signalBufferReturned(MediaBuffer *buffer) {
    LOGV("signalBufferReturned: %p", buffer->data());
    for (List<sp<IMemory> >::iterator it = mFramesBeingEncoded.begin();
         it != mFramesBeingEncoded.end(); ++it) {
        if ((*it)->pointer() ==  buffer->data()) {
            releaseOneRecordingFrame((*it));
            mFramesBeingEncoded.erase(it);
            ++mNumFramesEncoded;
            buffer->setObserver(0);
            buffer->release();
            mFrameCompleteCondition.signal();
            return;
        }
    }
    CHECK_EQ(0, "signalBufferReturned: bogus buffer");
}

status_t CameraSource::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    LOGV("read");

    *buffer = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        return ERROR_UNSUPPORTED;
    }

    sp<IMemory> frame;
    int64_t frameTime;

    {
        Mutex::Autolock autoLock(mLock);
        while (mStarted && mFramesReceived.empty()) {
            mFrameAvailableCondition.wait(mLock);
        }
        if (!mStarted) {
            return OK;
        }
        frame = *mFramesReceived.begin();
        mFramesReceived.erase(mFramesReceived.begin());

        frameTime = *mFrameTimes.begin();
        mFrameTimes.erase(mFrameTimes.begin());

        mFramesBeingEncoded.push_back(frame);
        *buffer = new MediaBuffer(frame->pointer(), frame->size());
        (*buffer)->setObserver(this);
        (*buffer)->add_ref();
        (*buffer)->meta_data()->setInt64(kKeyTime, frameTime);
    }
    return OK;
}

void CameraSource::dataCallbackTimestamp(int64_t timestampUs,
        int32_t msgType, const sp<IMemory> &data) {
    LOGV("dataCallbackTimestamp: timestamp %lld us", timestampUs);
    Mutex::Autolock autoLock(mLock);
    if (!mStarted) {
        releaseOneRecordingFrame(data);
        ++mNumFramesReceived;
        ++mNumFramesDropped;
        return;
    }

    if (mNumFramesReceived > 0 &&
        timestampUs - mLastFrameTimestampUs > mGlitchDurationThresholdUs) {
        if (mNumGlitches % 10 == 0) {  // Don't spam the log
            LOGW("Long delay detected in video recording");
        }
        ++mNumGlitches;
    }

    // May need to skip frame or modify timestamp. Currently implemented
    // by the subclass CameraSourceTimeLapse.
    if(skipCurrentFrame(timestampUs)) {
        releaseOneRecordingFrame(data);
        return;
    }

    mLastFrameTimestampUs = timestampUs;
    if (mNumFramesReceived == 0) {
        mFirstFrameTimeUs = timestampUs;
        // Initial delay
        if (mStartTimeUs > 0) {
            if (timestampUs < mStartTimeUs) {
                // Frame was captured before recording was started
                // Drop it without updating the statistical data.
                releaseOneRecordingFrame(data);
                return;
            }
            mStartTimeUs = timestampUs - mStartTimeUs;
        }
    }
    ++mNumFramesReceived;

    mFramesReceived.push_back(data);
    int64_t timeUs = mStartTimeUs + (timestampUs - mFirstFrameTimeUs);
    mFrameTimes.push_back(timeUs);
    LOGV("initial delay: %lld, current time stamp: %lld",
        mStartTimeUs, timeUs);
    mFrameAvailableCondition.signal();
}

}  // namespace android
