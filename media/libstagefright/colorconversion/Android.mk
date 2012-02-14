LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                     \
        ColorConverter.cpp            \
        SoftwareRenderer.cpp

LOCAL_C_INCLUDES := \
        $(TOP)/frameworks/base/include/media/stagefright/openmax \
        $(TOP)/hardware/msm7k

LOCAL_MODULE:= libstagefright_color_conversion

ifeq ($(OMAP_ENHANCEMENT), true)
LOCAL_C_INCLUDES += $(TOP)/hardware/ti/omap4xxx/domx/omx_core/inc
endif

include $(BUILD_STATIC_LIBRARY)
