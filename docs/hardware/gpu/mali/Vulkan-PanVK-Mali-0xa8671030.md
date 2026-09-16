# Vulkan feature + extension set — on-device query

## Device / driver identity (queried live, not from spec sheets)

```
device0: Mali unknown 0xa8671030 MC6 api 1.4.352 vid 0x13b5 did 0xa8671030 type 1 (integrated)
driverID: 20  driverName: panvk
driverInfo: Mesa 26.2.0-devel
queue families (1): q0: flags=0x7 (GFX|COMPUTE|TRANSFER) count=2 tsBits=64
memory types (3): t0 flags=0x1 heap=0 / t1 flags=0xb heap=0 / t2 flags=0x7 heap=0
memory heaps (1): heap0 = 5498 MB, flags=0x1 (DEVICE_LOCAL)
```

> `MESA: warning: panvk: unknown gpu_id (0xa8671030) or variant (0x4);`
> `continuing with conservative "Mali (unknown Valhall)" defaults — expect issues`
> The driver does not recognise this GPU (MediaTek SoC, Mali Valhall-v10 family,
> product-major 7, 6 cores → G610-class) and runs on conservative defaults.

## Instance extensions (22, loader)

```
  VK_KHR_device_group_creation (v1)
  VK_KHR_display (v23)
  VK_KHR_external_fence_capabilities (v1)
  VK_KHR_external_memory_capabilities (v1)
  VK_KHR_external_semaphore_capabilities (v1)
  VK_KHR_get_display_properties2 (v1)
  VK_KHR_get_physical_device_properties2 (v2)
  VK_KHR_get_surface_capabilities2 (v1)
  VK_KHR_surface (v25)
  VK_KHR_surface_maintenance1 (v1)
  VK_KHR_xcb_surface (v6)
  VK_KHR_xlib_surface (v6)
  VK_EXT_acquire_drm_display (v1)
  VK_EXT_acquire_xlib_display (v1)
  VK_EXT_debug_report (v10)
  VK_EXT_debug_utils (v2)
  VK_EXT_direct_mode_display (v1)
  VK_EXT_display_surface_counter (v1)
  VK_EXT_headless_surface (v1)
  VK_EXT_surface_maintenance1 (v1)
  VK_KHR_portability_enumeration (v1)
  VK_LUNARG_direct_driver_loading (v1)
```

## Device extensions (168)

```
  VK_KHR_8bit_storage (ver 1)
  VK_KHR_16bit_storage (ver 1)
  VK_KHR_bind_memory2 (ver 1)
  VK_KHR_buffer_device_address (ver 1)
  VK_KHR_calibrated_timestamps (ver 1)
  VK_KHR_copy_commands2 (ver 1)
  VK_KHR_create_renderpass2 (ver 1)
  VK_KHR_dedicated_allocation (ver 3)
  VK_KHR_depth_clamp_zero_one (ver 1)
  VK_KHR_depth_stencil_resolve (ver 1)
  VK_KHR_descriptor_update_template (ver 1)
  VK_KHR_device_group (ver 4)
  VK_KHR_draw_indirect_count (ver 1)
  VK_KHR_driver_properties (ver 1)
  VK_KHR_dynamic_rendering (ver 1)
  VK_KHR_dynamic_rendering_local_read (ver 1)
  VK_KHR_external_fence (ver 1)
  VK_KHR_external_fence_fd (ver 1)
  VK_KHR_external_memory (ver 1)
  VK_KHR_external_memory_fd (ver 1)
  VK_KHR_external_semaphore (ver 1)
  VK_KHR_external_semaphore_fd (ver 1)
  VK_KHR_format_feature_flags2 (ver 2)
  VK_KHR_get_memory_requirements2 (ver 1)
  VK_KHR_global_priority (ver 1)
  VK_KHR_image_format_list (ver 1)
  VK_KHR_imageless_framebuffer (ver 1)
  VK_KHR_index_type_uint8 (ver 1)
  VK_KHR_line_rasterization (ver 1)
  VK_KHR_load_store_op_none (ver 1)
  VK_KHR_maintenance1 (ver 2)
  VK_KHR_maintenance2 (ver 1)
  VK_KHR_maintenance3 (ver 1)
  VK_KHR_maintenance4 (ver 2)
  VK_KHR_maintenance5 (ver 1)
  VK_KHR_maintenance6 (ver 1)
  VK_KHR_maintenance7 (ver 1)
  VK_KHR_maintenance8 (ver 1)
  VK_KHR_maintenance9 (ver 1)
  VK_KHR_map_memory2 (ver 1)
  VK_KHR_multiview (ver 1)
  VK_KHR_pipeline_binary (ver 1)
  VK_KHR_pipeline_executable_properties (ver 1)
  VK_KHR_pipeline_library (ver 1)
  VK_KHR_present_id (ver 1)
  VK_KHR_present_id2 (ver 1)
  VK_KHR_present_wait (ver 1)
  VK_KHR_present_wait2 (ver 1)
  VK_KHR_push_descriptor (ver 2)
  VK_KHR_relaxed_block_layout (ver 1)
  VK_KHR_robustness2 (ver 1)
  VK_KHR_sampler_mirror_clamp_to_edge (ver 3)
  VK_KHR_sampler_ycbcr_conversion (ver 14)
  VK_KHR_separate_depth_stencil_layouts (ver 1)
  VK_KHR_shader_atomic_int64 (ver 1)
  VK_KHR_shader_clock (ver 1)
  VK_KHR_shader_draw_parameters (ver 1)
  VK_KHR_shader_expect_assume (ver 1)
  VK_KHR_shader_float16_int8 (ver 1)
  VK_KHR_shader_float_controls (ver 4)
  VK_KHR_shader_float_controls2 (ver 1)
  VK_KHR_shader_integer_dot_product (ver 1)
  VK_KHR_shader_maximal_reconvergence (ver 1)
  VK_KHR_shader_non_semantic_info (ver 1)
  VK_KHR_shader_quad_control (ver 1)
  VK_KHR_shader_relaxed_extended_instruction (ver 1)
  VK_KHR_shader_subgroup_extended_types (ver 1)
  VK_KHR_shader_subgroup_rotate (ver 2)
  VK_KHR_shader_subgroup_uniform_control_flow (ver 1)
  VK_KHR_shader_terminate_invocation (ver 1)
  VK_KHR_shader_untyped_pointers (ver 1)
  VK_KHR_spirv_1_4 (ver 1)
  VK_KHR_storage_buffer_storage_class (ver 1)
  VK_KHR_swapchain (ver 70)
  VK_KHR_swapchain_maintenance1 (ver 1)
  VK_KHR_swapchain_mutable_format (ver 1)
  VK_KHR_synchronization2 (ver 1)
  VK_KHR_timeline_semaphore (ver 2)
  VK_KHR_unified_image_layouts (ver 1)
  VK_KHR_uniform_buffer_standard_layout (ver 1)
  VK_KHR_variable_pointers (ver 1)
  VK_KHR_vertex_attribute_divisor (ver 1)
  VK_KHR_vulkan_memory_model (ver 3)
  VK_KHR_zero_initialize_workgroup_memory (ver 1)
  VK_EXT_4444_formats (ver 1)
  VK_EXT_astc_decode_mode (ver 1)
  VK_EXT_attachment_feedback_loop_dynamic_state (ver 1)
  VK_EXT_attachment_feedback_loop_layout (ver 2)
  VK_EXT_border_color_swizzle (ver 1)
  VK_EXT_buffer_device_address (ver 2)
  VK_EXT_calibrated_timestamps (ver 2)
  VK_EXT_color_write_enable (ver 1)
  VK_EXT_conditional_rendering (ver 2)
  VK_EXT_custom_border_color (ver 12)
  VK_EXT_depth_bias_control (ver 1)
  VK_EXT_depth_clamp_control (ver 1)
  VK_EXT_depth_clamp_zero_one (ver 1)
  VK_EXT_depth_clip_control (ver 1)
  VK_EXT_depth_clip_enable (ver 1)
  VK_EXT_descriptor_indexing (ver 2)
  VK_EXT_device_memory_report (ver 2)
  VK_EXT_display_control (ver 1)
  VK_EXT_dynamic_rendering_unused_attachments (ver 1)
  VK_EXT_extended_dynamic_state (ver 1)
  VK_EXT_extended_dynamic_state2 (ver 1)
  VK_EXT_extended_dynamic_state3 (ver 2)
  VK_EXT_external_memory_acquire_unmodified (ver 1)
  VK_EXT_external_memory_dma_buf (ver 1)
  VK_EXT_global_priority (ver 2)
  VK_EXT_global_priority_query (ver 1)
  VK_EXT_graphics_pipeline_library (ver 1)
  VK_EXT_hdr_metadata (ver 3)
  VK_EXT_host_image_copy (ver 1)
  VK_EXT_host_query_reset (ver 1)
  VK_EXT_image_2d_view_of_3d (ver 1)
  VK_EXT_image_drm_format_modifier (ver 2)
  VK_EXT_image_robustness (ver 1)
  VK_EXT_image_view_min_lod (ver 1)
  VK_EXT_index_type_uint8 (ver 1)
  VK_EXT_inline_uniform_block (ver 1)
  VK_EXT_legacy_dithering (ver 2)
  VK_EXT_line_rasterization (ver 1)
  VK_EXT_load_store_op_none (ver 1)
  VK_EXT_map_memory_placed (ver 1)
  VK_EXT_memory_budget (ver 1)
  VK_EXT_multisampled_render_to_single_sampled (ver 1)
  VK_EXT_mutable_descriptor_type (ver 1)
  VK_EXT_nested_command_buffer (ver 1)
  VK_EXT_non_seamless_cube_map (ver 1)
  VK_EXT_physical_device_drm (ver 1)
  VK_EXT_pipeline_creation_cache_control (ver 3)
  VK_EXT_pipeline_creation_feedback (ver 1)
  VK_EXT_pipeline_robustness (ver 1)
  VK_EXT_present_timing (ver 3)
  VK_EXT_primitive_topology_list_restart (ver 1)
  VK_EXT_private_data (ver 1)
  VK_EXT_provoking_vertex (ver 1)
  VK_EXT_queue_family_foreign (ver 1)
  VK_EXT_robustness2 (ver 1)
  VK_EXT_sampler_filter_minmax (ver 2)
  VK_EXT_scalar_block_layout (ver 1)
  VK_EXT_separate_stencil_usage (ver 1)
  VK_EXT_shader_atomic_float (ver 1)
  VK_EXT_shader_demote_to_helper_invocation (ver 1)
  VK_EXT_shader_module_identifier (ver 1)
  VK_EXT_shader_replicated_composites (ver 1)
  VK_EXT_shader_stencil_export (ver 1)
  VK_EXT_shader_subgroup_ballot (ver 1)
  VK_EXT_shader_subgroup_vote (ver 1)
  VK_EXT_shader_uniform_buffer_unsized_array (ver 1)
  VK_EXT_subgroup_size_control (ver 2)
  VK_EXT_swapchain_maintenance1 (ver 1)
  VK_EXT_texel_buffer_alignment (ver 1)
  VK_EXT_texture_compression_astc_hdr (ver 1)
  VK_EXT_tooling_info (ver 1)
  VK_EXT_transform_feedback (ver 1)
  VK_EXT_vertex_attribute_divisor (ver 3)
  VK_EXT_vertex_input_dynamic_state (ver 2)
  VK_EXT_ycbcr_2plane_444_formats (ver 1)
  VK_EXT_ycbcr_image_arrays (ver 1)
  VK_EXT_zero_initialize_device_memory (ver 1)
  VK_ARM_scheduling_controls (ver 2)
  VK_ARM_shader_core_builtins (ver 2)
  VK_ARM_shader_core_properties (ver 1)
  VK_GOOGLE_decorate_string (ver 1)
  VK_GOOGLE_hlsl_functionality1 (ver 1)
  VK_GOOGLE_user_type (ver 1)
  VK_VALVE_mutable_descriptor_type (ver 1)
```

## Core features

### Vulkan 1.0 (55 features)

```
  robustBufferAccess                                   YES
  fullDrawIndexUint32                                  YES
  imageCubeArray                                       YES
  independentBlend                                     YES
  geometryShader                                       no
  tessellationShader                                   no
  sampleRateShading                                    YES
  dualSrcBlend                                         YES
  logicOp                                              YES
  multiDrawIndirect                                    YES
  drawIndirectFirstInstance                            YES
  depthClamp                                           YES
  depthBiasClamp                                       YES
  fillModeNonSolid                                     YES
  depthBounds                                          no
  wideLines                                            YES
  largePoints                                          YES
  alphaToOne                                           no
  multiViewport                                        no
  samplerAnisotropy                                    YES
  textureCompressionETC2                               YES
  textureCompressionASTC_LDR                           YES
  textureCompressionBC                                 no
  occlusionQueryPrecise                                YES
  pipelineStatisticsQuery                              no
  vertexPipelineStoresAndAtomics                       no
  fragmentStoresAndAtomics                             YES
  shaderImageGatherExtended                            YES
  shaderStorageImageExtendedFormats                    YES
  shaderStorageImageMultisample                        no
  shaderStorageImageReadWithoutFormat                  YES
  shaderStorageImageWriteWithoutFormat                 YES
  shaderUniformBufferArrayDynamicIndexing              YES
  shaderSampledImageArrayDynamicIndexing               YES
  shaderStorageBufferArrayDynamicIndexing              YES
  shaderStorageImageArrayDynamicIndexing               YES
  shaderClipDistance                                   no
  shaderCullDistance                                   no
  shaderFloat64                                        no
  shaderInt64                                          YES
  shaderInt16                                          YES
  shaderResourceResidency                              no
  shaderResourceMinLod                                 no
  sparseBinding                                        no
  sparseResidencyBuffer                                no
  sparseResidencyImage2D                               no
  sparseResidencyImage3D                               no
  sparseResidency2Samples                              no
  sparseResidency4Samples                              no
  sparseResidency8Samples                              no
  sparseResidency16Samples                             no
  sparseResidencyAliased                               no
  variableMultisampleRate                              no
  inheritedQueries                                     no
```

### Vulkan 1.1

```
multiview                                            YES
  shaderDrawParameters                                 YES
  samplerYcbcrConversion                               YES
  variablePointers                                     YES
  storageBuffer16BitAccess                             YES
  uniformAndStorageBuffer16BitAccess                   YES
  storagePushConstant16                                YES
  storageInputOutput16                                 YES
```

### Vulkan 1.2

```
drawIndirectCount                                    YES
  storageBuffer8BitAccess                              YES
  uniformAndStorageBuffer8BitAccess                    YES
  shaderFloat16                                        YES
  shaderInt8                                           YES
  shaderBufferInt64Atomics                             YES
  shaderSharedInt64Atomics                             YES
  descriptorIndexing                                   YES
  runtimeDescriptorArray                               YES
  samplerFilterMinmax                                  YES
  scalarBlockLayout                                    YES
  imagelessFramebuffer                                 YES
  uniformBufferStandardLayout                          YES
  shaderSubgroupExtendedTypes                          YES
  separateDepthStencilLayouts                          YES
  hostQueryReset                                       YES
  timelineSemaphore                                    YES
  bufferDeviceAddress                                  YES
  vulkanMemoryModel                                    YES
  shaderOutputViewportIndex                            no
  subgroupBroadcastDynamicId                           YES
```

### Vulkan 1.3

```
robustImageAccess                                    YES
  inlineUniformBlock                                   YES
  pipelineCreationCacheControl                         YES
  descriptorBindingInlineUniformBlockUpdateAfterBind   YES
  privateData                                          YES
  shaderDemoteToHelperInvocation                       YES
  shaderTerminateInvocation                            YES
  subgroupSizeControl                                  YES
  computeFullSubgroups                                 YES
  synchronization2                                     YES
  textureCompressionASTC_HDR                           YES
  shaderZeroInitializeWorkgroupMemory                  YES
  dynamicRendering                                     YES
  shaderIntegerDotProduct                              YES
  maintenance4                                         YES
```

### Vulkan 1.4

```
globalPriorityQuery                                  YES
  shaderSubgroupRotate                                 YES
  shaderSubgroupRotateClustered                        YES
  shaderFloatControls2                                 YES
  shaderExpectAssume                                   YES
  rectangularLines                                     YES
  bresenhamLines                                       YES
  smoothLines                                          no
  stippledRectangularLines                             no
  stippledBresenhamLines                               no
  stippledSmoothLines                                  no
  vertexAttributeInstanceRateDivisor                   YES
  vertexAttributeInstanceRateZeroDivisor               YES
  indexTypeUint8                                       YES
  dynamicRenderingLocalRead                            YES
  maintenance5                                         YES
  maintenance6                                         YES
  pipelineProtectedAccess                              no
  pipelineRobustness                                   YES
  hostImageCopy                                        YES
  pushDescriptor                                       YES
```

## How obtained

- No `vulkaninfo` on device. Wrote `/opt/nativecode/panvk/ext_probe.c` (instance +
  device enumeration, 168 extensions) and `/tmp/vkfeat.c` + `/tmp/vkfeat10.c`
  (features 1.0–1.4, compiled against real Vulkan-Headers v1.4.352).
- Env: `LD_LIBRARY_PATH=/opt/nativecode/panvk/lib`
  `VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/panvk_kbase.json`.
- Raw outputs: `/tmp/vk_ext_full.txt`, `/tmp/vk_feat_full.txt`, `/tmp/vk_feat10_full.txt`.
