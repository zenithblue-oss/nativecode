#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#define F(field)                                                               \
    do {                                                                       \
        if (f.field)                                                           \
            printf("  %s\n", #field);                                          \
    } while (0)

static const char *dtype(VkPhysicalDeviceType t)
{
    switch (t) {
    case VK_PHYSICAL_DEVICE_TYPE_OTHER:
        return "OTHER";
    case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU:
        return "INTEGRATED_GPU";
    case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:
        return "DISCRETE_GPU";
    case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:
        return "VIRTUAL_GPU";
    case VK_PHYSICAL_DEVICE_TYPE_CPU:
        return "CPU";
    default:
        return "UNKNOWN";
    }
}

int main(void)
{
    const char *icd = getenv("VK_DRIVER_FILES");
    const char *icd2 = getenv("VK_ICD_FILENAMES");
    printf("ICD VK_DRIVER_FILES=%s\n", icd ? icd : "(unset)");
    printf("ICD VK_ICD_FILENAMES=%s\n", icd2 ? icd2 : "(unset)");

    VkApplicationInfo app = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "nc_vk_features",
        .apiVersion = VK_API_VERSION_1_3,
    };
    VkInstanceCreateInfo ci = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &app,
    };
    VkInstance inst;
    VkResult r = vkCreateInstance(&ci, NULL, &inst);
    printf("vkCreateInstance=%d\n", r);
    if (r != VK_SUCCESS)
        return 1;

    uint32_t n = 0;
    r = vkEnumeratePhysicalDevices(inst, &n, NULL);
    if (r != VK_SUCCESS || n == 0) {
        printf("no physical devices r=%d n=%u\n", r, n);
        vkDestroyInstance(inst, NULL);
        return 1;
    }
    VkPhysicalDevice *devs = malloc(sizeof(*devs) * n);
    vkEnumeratePhysicalDevices(inst, &n, devs);

    for (uint32_t i = 0; i < n; i++) {
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(devs[i], &p);
        printf("\nDEVICE %u\n", i);
        printf("deviceName=%s\n", p.deviceName);
        printf("vendorID=0x%04x\n", p.vendorID);
        printf("deviceID=0x%08x\n", p.deviceID);
        printf("deviceType=%s (%d)\n", dtype(p.deviceType), p.deviceType);
        printf("apiVersion=%u.%u.%u\n", VK_API_VERSION_MAJOR(p.apiVersion),
               VK_API_VERSION_MINOR(p.apiVersion),
               VK_API_VERSION_PATCH(p.apiVersion));
        printf("driverVersion=0x%x (%u)\n", p.driverVersion, p.driverVersion);

        VkPhysicalDeviceFeatures f;
        vkGetPhysicalDeviceFeatures(devs[i], &f);
        printf("FEATURES_TRUE\n");
        F(robustBufferAccess);
        F(fullDrawIndexUint32);
        F(imageCubeArray);
        F(independentBlend);
        F(geometryShader);
        F(tessellationShader);
        F(sampleRateShading);
        F(dualSrcBlend);
        F(logicOp);
        F(multiDrawIndirect);
        F(drawIndirectFirstInstance);
        F(depthClamp);
        F(depthBiasClamp);
        F(fillModeNonSolid);
        F(depthBounds);
        F(wideLines);
        F(largePoints);
        F(alphaToOne);
        F(multiViewport);
        F(samplerAnisotropy);
        F(textureCompressionETC2);
        F(textureCompressionASTC_LDR);
        F(textureCompressionBC);
        F(occlusionQueryPrecise);
        F(pipelineStatisticsQuery);
        F(vertexPipelineStoresAndAtomics);
        F(fragmentStoresAndAtomics);
        F(shaderTessellationAndGeometryPointSize);
        F(shaderImageGatherExtended);
        F(shaderStorageImageExtendedFormats);
        F(shaderStorageImageMultisample);
        F(shaderStorageImageReadWithoutFormat);
        F(shaderStorageImageWriteWithoutFormat);
        F(shaderUniformBufferArrayDynamicIndexing);
        F(shaderSampledImageArrayDynamicIndexing);
        F(shaderStorageBufferArrayDynamicIndexing);
        F(shaderStorageImageArrayDynamicIndexing);
        F(shaderClipDistance);
        F(shaderCullDistance);
        F(shaderFloat64);
        F(shaderInt64);
        F(shaderInt16);
        F(shaderResourceResidency);
        F(shaderResourceMinLod);
        F(sparseBinding);
        F(sparseResidencyBuffer);
        F(sparseResidencyImage2D);
        F(sparseResidencyImage3D);
        F(sparseResidency2Samples);
        F(sparseResidency4Samples);
        F(sparseResidency8Samples);
        F(sparseResidency16Samples);
        F(sparseResidencyAliased);
        F(variableMultisampleRate);
        F(inheritedQueries);

        uint32_t ext_n = 0;
        vkEnumerateDeviceExtensionProperties(devs[i], NULL, &ext_n, NULL);
        printf("EXTENSION_COUNT=%u\n", ext_n);
        VkExtensionProperties *exts = calloc(ext_n, sizeof(*exts));
        vkEnumerateDeviceExtensionProperties(devs[i], NULL, &ext_n, exts);
        printf("EXTENSIONS\n");
        for (uint32_t e = 0; e < ext_n; e++)
            printf("  %s spec=%u\n", exts[e].extensionName, exts[e].specVersion);
        free(exts);
    }
    free(devs);
    vkDestroyInstance(inst, NULL);
    return 0;
}
