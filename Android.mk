LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional
# Reference java/ source files
LOCAL_SRC_FILES := \
    $(call all-java-files-under, java)

LOCAL_STATIC_JAVA_LIBRARIES := \
	android-support-v4
	
# Re-map res/ directly
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/app/src/main/res

LOCAL_PACKAGE_NAME := UnlegacyUpdater
LOCAL_SDK_VERSION := current
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)