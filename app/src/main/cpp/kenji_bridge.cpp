// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// A small, real Vulkan diagnostics library. The emulator itself remains the
// official libkenjinx.so + libkenjinxjni.so pair. This library only asks the
// Android Vulkan loader for the physical-device properties so the player can
// show the driver which is actually active on the phone.

#include <jni.h>
#include <vulkan/vulkan.h>
#include <android/log.h>

#include <sstream>
#include <string>
#include <vector>

#define LOG_TAG "SymbiosisKenji"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

const char* resultName(VkResult result) {
    switch (result) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_NOT_READY: return "VK_NOT_READY";
        case VK_TIMEOUT: return "VK_TIMEOUT";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
        case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
        default: return "Vulkan error";
    }
}

std::string versionString(uint32_t version) {
    std::ostringstream out;
    out << VK_VERSION_MAJOR(version) << "."
        << VK_VERSION_MINOR(version) << "."
        << VK_VERSION_PATCH(version);
    return out.str();
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_org_kenjinx_android_NativeHelpers_getVulkanDriverInfo(JNIEnv* env, jobject) {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "Kenji Space diagnostics";
    appInfo.applicationVersion = 1;
    appInfo.pEngineName = "Kenji-NX";
    appInfo.engineVersion = 1;
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    const VkResult createResult = vkCreateInstance(&createInfo, nullptr, &instance);
    if (createResult != VK_SUCCESS) {
        const std::string text = "Vulkan недоступен: " +
            std::string(resultName(createResult)) + " (" + std::to_string(createResult) + ")";
        LOGE("%s", text.c_str());
        return env->NewStringUTF(text.c_str());
    }

    uint32_t deviceCount = 0;
    VkResult result = vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (result != VK_SUCCESS || deviceCount == 0) {
        vkDestroyInstance(instance, nullptr);
        const std::string text = "Vulkan: физическое устройство не найдено (" +
            std::string(resultName(result)) + ")";
        return env->NewStringUTF(text.c_str());
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    result = vkEnumeratePhysicalDevices(instance, &deviceCount, devices.data());
    if (result != VK_SUCCESS || devices.empty()) {
        vkDestroyInstance(instance, nullptr);
        const std::string text = "Vulkan: не удалось перечислить устройства (" +
            std::string(resultName(result)) + ")";
        return env->NewStringUTF(text.c_str());
    }

    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(devices.front(), &properties);
    vkDestroyInstance(instance, nullptr);

    std::ostringstream out;
    out << properties.deviceName
        << " · vendor 0x" << std::hex << properties.vendorID << std::dec
        << " · driver " << versionString(properties.driverVersion)
        << " · Vulkan " << versionString(properties.apiVersion);
    const std::string text = out.str();
    return env->NewStringUTF(text.c_str());
}

} // extern "C"
