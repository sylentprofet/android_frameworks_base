/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_HWUI_PATCH_CACHE_H
#define ANDROID_HWUI_PATCH_CACHE_H

#include <utils/KeyedVector.h>

#include "Debug.h"
#include "Patch.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#if DEBUG_PATCHES
    #define PATCH_LOGD(...) LOGD(__VA_ARGS__)
#else
    #define PATCH_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Cache
///////////////////////////////////////////////////////////////////////////////

class PatchCache {
public:
    PatchCache();
    PatchCache(uint32_t maxCapacity);
    ~PatchCache();

    Patch* get(const float bitmapWidth, const float bitmapHeight,
            const float pixelWidth, const float pixelHeight,
            const int32_t* xDivs, const int32_t* yDivs, const uint32_t* colors,
            const uint32_t width, const uint32_t height, const int8_t numColors);
    void clear();

    uint32_t getSize() const {
        return mCache.size();
    }

    uint32_t getMaxSize() const {
        return mMaxEntries;
    }

private:
    uint32_t mMaxEntries;
    KeyedVector<PatchDescription, Patch*> mCache;
}; // class PatchCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PATCH_CACHE_H
