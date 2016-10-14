LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := \
    $(call all-java-files-under, java)

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-v4

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_PACKAGE_NAME := UnlegacyUpdater
LOCAL_SDK_VERSION := current
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_CERTIFICATE := platform

LOCAL_PRIVILEGED_MODULE := true

include $(BUILD_PACKAGE)