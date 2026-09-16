# Vulkan on Adreno 750v2 (FD750) — Turnip Driver

> **Source**: Enumerated on-device via Turnip ICD (`libvulkan_freedreno.so`) on the live GPU.
> Mesa version: 26.3.0-devel (git-98f3d6229d), API version 1.4.359

---

## Physical Device

| Property        | Value |
|-----------------|-------|
| deviceName      | Turnip Adreno (TM) 750 |
| vendorID        | 0x5143 |
| deviceID        | 0x43051401 |
| deviceType      | INTEGRATED_GPU |
| apiVersion      | 1.4.359 |
| driverVersion   | 26.2.24.3 |

---

## Instance Extensions (22)

| Extension | specVersion |
|-----------|-------------|
| VK_KHR_device_group_creation | 1 |
| VK_KHR_display | 23 |
| VK_KHR_external_fence_capabilities | 1 |
| VK_KHR_external_memory_capabilities | 1 |
| VK_KHR_external_semaphore_capabilities | 1 |
| VK_KHR_get_display_properties2 | 1 |
| VK_KHR_get_physical_device_properties2 | 2 |
| VK_KHR_get_surface_capabilities2 | 1 |
| VK_KHR_surface | 25 |
| VK_KHR_surface_maintenance1 | 1 |
| VK_KHR_surface_protected_capabilities | 1 |
| VK_KHR_xcb_surface | 6 |
| VK_KHR_xlib_surface | 6 |
| VK_EXT_acquire_drm_display | 1 |
| VK_EXT_acquire_xlib_display | 1 |
| VK_EXT_debug_report | 10 |
| VK_EXT_debug_utils | 2 |
| VK_EXT_direct_mode_display | 1 |
| VK_EXT_display_surface_counter | 1 |
| VK_EXT_headless_surface | 1 |
| VK_EXT_surface_maintenance1 | 1 |
| VK_EXT_swapchain_colorspace | 5 |

---

## Device Extensions (189)

### KHR (89)

| Extension | specVersion |
|-----------|-------------|
| VK_KHR_8bit_storage | 1 |
| VK_KHR_16bit_storage | 1 |
| VK_KHR_acceleration_structure | 13 |
| VK_KHR_bind_memory2 | 1 |
| VK_KHR_buffer_device_address | 1 |
| VK_KHR_calibrated_timestamps | 1 |
| VK_KHR_compute_shader_derivatives | 1 |
| VK_KHR_copy_commands2 | 1 |
| VK_KHR_create_renderpass2 | 1 |
| VK_KHR_dedicated_allocation | 3 |
| VK_KHR_deferred_host_operations | 4 |
| VK_KHR_depth_clamp_zero_one | 1 |
| VK_KHR_depth_stencil_resolve | 1 |
| VK_KHR_descriptor_update_template | 1 |
| VK_KHR_device_group | 4 |
| VK_KHR_draw_indirect_count | 1 |
| VK_KHR_driver_properties | 1 |
| VK_KHR_dynamic_rendering | 1 |
| VK_KHR_dynamic_rendering_local_read | 1 |
| VK_KHR_external_fence | 1 |
| VK_KHR_external_fence_fd | 1 |
| VK_KHR_external_memory | 1 |
| VK_KHR_external_memory_fd | 1 |
| VK_KHR_external_semaphore | 1 |
| VK_KHR_external_semaphore_fd | 1 |
| VK_KHR_format_feature_flags2 | 2 |
| VK_KHR_fragment_shading_rate | 2 |
| VK_KHR_get_memory_requirements2 | 1 |
| VK_KHR_global_priority | 1 |
| VK_KHR_image_format_list | 1 |
| VK_KHR_imageless_framebuffer | 1 |
| VK_KHR_incremental_present | 2 |
| VK_KHR_index_type_uint8 | 1 |
| VK_KHR_internally_synchronized_queues | 1 |
| VK_KHR_line_rasterization | 1 |
| VK_KHR_load_store_op_none | 1 |
| VK_KHR_maintenance1 | 2 |
| VK_KHR_maintenance2 | 1 |
| VK_KHR_maintenance3 | 1 |
| VK_KHR_maintenance4 | 2 |
| VK_KHR_maintenance5 | 1 |
| VK_KHR_maintenance6 | 1 |
| VK_KHR_maintenance7 | 1 |
| VK_KHR_maintenance8 | 1 |
| VK_KHR_maintenance9 | 1 |
| VK_KHR_map_memory2 | 1 |
| VK_KHR_multiview | 1 |
| VK_KHR_pipeline_executable_properties | 1 |
| VK_KHR_pipeline_library | 1 |
| VK_KHR_present_id | 1 |
| VK_KHR_present_id2 | 1 |
| VK_KHR_present_wait | 1 |
| VK_KHR_present_wait2 | 1 |
| VK_KHR_push_descriptor | 2 |
| VK_KHR_ray_query | 1 |
| VK_KHR_ray_tracing_maintenance1 | 1 |
| VK_KHR_relaxed_block_layout | 1 |
| VK_KHR_robustness2 | 1 |
| VK_KHR_sampler_mirror_clamp_to_edge | 3 |
| VK_KHR_sampler_ycbcr_conversion | 14 |
| VK_KHR_separate_depth_stencil_layouts | 1 |
| VK_KHR_shader_atomic_int64 | 1 |
| VK_KHR_shader_clock | 1 |
| VK_KHR_shader_draw_parameters | 1 |
| VK_KHR_shader_expect_assume | 1 |
| VK_KHR_shader_float16_int8 | 1 |
| VK_KHR_shader_float_controls | 4 |
| VK_KHR_shader_float_controls2 | 1 |
| VK_KHR_shader_integer_dot_product | 1 |
| VK_KHR_shader_non_semantic_info | 1 |
| VK_KHR_shader_relaxed_extended_instruction | 1 |
| VK_KHR_shader_subgroup_extended_types | 1 |
| VK_KHR_shader_subgroup_rotate | 2 |
| VK_KHR_shader_subgroup_uniform_control_flow | 1 |
| VK_KHR_shader_terminate_invocation | 1 |
| VK_KHR_spirv_1_4 | 1 |
| VK_KHR_storage_buffer_storage_class | 1 |
| VK_KHR_swapchain | 70 |
| VK_KHR_swapchain_maintenance1 | 1 |
| VK_KHR_swapchain_mutable_format | 1 |
| VK_KHR_synchronization2 | 1 |
| VK_KHR_timeline_semaphore | 2 |
| VK_KHR_unified_image_layouts | 1 |
| VK_KHR_uniform_buffer_standard_layout | 1 |
| VK_KHR_variable_pointers | 1 |
| VK_KHR_vertex_attribute_divisor | 1 |
| VK_KHR_vulkan_memory_model | 3 |
| VK_KHR_workgroup_memory_explicit_layout | 1 |
| VK_KHR_zero_initialize_workgroup_memory | 1 |

### EXT (86)

| Extension | specVersion |
|-----------|-------------|
| VK_EXT_4444_formats | 1 |
| VK_EXT_attachment_feedback_loop_dynamic_state | 1 |
| VK_EXT_attachment_feedback_loop_layout | 2 |
| VK_EXT_border_color_swizzle | 1 |
| VK_EXT_calibrated_timestamps | 2 |
| VK_EXT_color_write_enable | 1 |
| VK_EXT_conditional_rendering | 2 |
| VK_EXT_conservative_rasterization | 1 |
| VK_EXT_custom_border_color | 12 |
| VK_EXT_custom_resolve | 1 |
| VK_EXT_debug_marker | 4 |
| VK_EXT_depth_clamp_control | 1 |
| VK_EXT_depth_clamp_zero_one | 1 |
| VK_EXT_depth_clip_control | 1 |
| VK_EXT_depth_clip_enable | 1 |
| VK_EXT_descriptor_buffer | 1 |
| VK_EXT_descriptor_indexing | 2 |
| VK_EXT_device_address_binding_report | 1 |
| VK_EXT_device_memory_report | 2 |
| VK_EXT_display_control | 1 |
| VK_EXT_dynamic_rendering_unused_attachments | 1 |
| VK_EXT_extended_dynamic_state | 1 |
| VK_EXT_extended_dynamic_state2 | 1 |
| VK_EXT_extended_dynamic_state3 | 2 |
| VK_EXT_external_memory_dma_buf | 1 |
| VK_EXT_filter_cubic | 3 |
| VK_EXT_fragment_density_map | 3 |
| VK_EXT_fragment_density_map_offset | 1 |
| VK_EXT_global_priority | 2 |
| VK_EXT_global_priority_query | 1 |
| VK_EXT_graphics_pipeline_library | 1 |
| VK_EXT_hdr_metadata | 3 |
| VK_EXT_host_image_copy | 1 |
| VK_EXT_host_query_reset | 1 |
| VK_EXT_image_2d_view_of_3d | 1 |
| VK_EXT_image_compression_control | 1 |
| VK_EXT_image_compression_control_swapchain | 1 |
| VK_EXT_image_drm_format_modifier | 2 |
| VK_EXT_image_robustness | 1 |
| VK_EXT_image_view_min_lod | 1 |
| VK_EXT_index_type_uint8 | 1 |
| VK_EXT_inline_uniform_block | 1 |
| VK_EXT_legacy_dithering | 2 |
| VK_EXT_legacy_vertex_attributes | 1 |
| VK_EXT_line_rasterization | 1 |
| VK_EXT_load_store_op_none | 1 |
| VK_EXT_map_memory_placed | 1 |
| VK_EXT_memory_budget | 1 |
| VK_EXT_multi_draw | 1 |
| VK_EXT_multisampled_render_to_single_sampled | 1 |
| VK_EXT_mutable_descriptor_type | 1 |
| VK_EXT_nested_command_buffer | 1 |
| VK_EXT_non_seamless_cube_map | 1 |
| VK_EXT_pipeline_creation_cache_control | 3 |
| VK_EXT_pipeline_creation_feedback | 1 |
| VK_EXT_present_timing | 3 |
| VK_EXT_primitive_restart_index | 1 |
| VK_EXT_primitive_topology_list_restart | 1 |
| VK_EXT_primitives_generated_query | 1 |
| VK_EXT_private_data | 1 |
| VK_EXT_provoking_vertex | 1 |
| VK_EXT_queue_family_foreign | 1 |
| VK_EXT_rasterization_order_attachment_access | 1 |
| VK_EXT_robustness2 | 1 |
| VK_EXT_sample_locations | 1 |
| VK_EXT_sampler_filter_minmax | 2 |
| VK_EXT_scalar_block_layout | 1 |
| VK_EXT_separate_stencil_usage | 1 |
| VK_EXT_shader_atomic_float | 1 |
| VK_EXT_shader_demote_to_helper_invocation | 1 |
| VK_EXT_shader_image_atomic_int64 | 1 |
| VK_EXT_shader_module_identifier | 1 |
| VK_EXT_shader_replicated_composites | 1 |
| VK_EXT_shader_stencil_export | 1 |
| VK_EXT_shader_subgroup_vote | 1 |
| VK_EXT_shader_uniform_buffer_unsized_array | 1 |
| VK_EXT_shader_viewport_index_layer | 1 |
| VK_EXT_subgroup_size_control | 2 |
| VK_EXT_swapchain_maintenance1 | 1 |
| VK_EXT_texel_buffer_alignment | 1 |
| VK_EXT_texture_compression_astc_hdr | 1 |
| VK_EXT_tooling_info | 1 |
| VK_EXT_transform_feedback | 1 |
| VK_EXT_vertex_attribute_divisor | 3 |
| VK_EXT_vertex_input_dynamic_state | 2 |
| VK_EXT_zero_initialize_device_memory | 1 |

### Vendor (14)

| Extension | specVersion |
|-----------|-------------|
| VK_AMD_buffer_marker | 1 |
| VK_ARM_rasterization_order_attachment_access | 1 |
| VK_GOOGLE_decorate_string | 1 |
| VK_GOOGLE_hlsl_functionality1 | 1 |
| VK_GOOGLE_user_type | 1 |
| VK_IMG_filter_cubic | 1 |
| VK_NV_compute_shader_derivatives | 1 |
| VK_QCOM_fragment_density_map_offset | 3 |
| VK_QCOM_image_processing | 1 |
| VK_QCOM_multiview_per_view_render_areas | 1 |
| VK_QCOM_multiview_per_view_viewports | 1 |
| VK_QCOM_render_pass_shader_resolve | 4 |
| VK_VALVE_fragment_density_map_layered | 1 |
| VK_VALVE_mutable_descriptor_type | 1 |

---

## Device Features (VkPhysicalDeviceFeatures)

| Feature | Supported |
|---------|-----------|
| robustBufferAccess | Yes |
| fullDrawIndexUint32 | Yes |
| imageCubeArray | Yes |
| independentBlend | Yes |
| geometryShader | Yes |
| tessellationShader | Yes |
| sampleRateShading | Yes |
| dualSrcBlend | Yes |
| logicOp | Yes |
| multiDrawIndirect | Yes |
| drawIndirectFirstInstance | Yes |
| depthClamp | Yes |
| depthBiasClamp | Yes |
| fillModeNonSolid | Yes |
| depthBounds | Yes |
| wideLines | Yes |
| largePoints | Yes |
| alphaToOne | Yes |
| multiViewport | Yes |
| samplerAnisotropy | Yes |
| textureCompressionETC2 | Yes |
| textureCompressionBC | Yes |
| textureCompressionASTC_LDR | Yes |
| occlusionQueryPrecise | Yes |
| pipelineStatisticsQuery | Yes |
| shaderFloat64 | No |
| shaderInt64 | Yes |
| shaderInt16 | Yes |
| sparseBinding | Yes |
| sparseResidencyBuffer | Yes |
| sparseResidencyImage2D | Yes |
| sparseResidencyImage3D | No |
| sparseResidencyAliased | Yes |
| variableMultisampleRate | Yes |
| inheritedQueries | Yes |

---

## Limits

| Limit | Value |
|-------|-------|
| maxImageDimension | 16384 |
| maxComputeWorkGroupCount | 65535 x 65535 x 65535 |
| maxComputeWorkGroupSize | 1024 x 1024 x 1024 |
| maxComputeWorkGroupInvocations | 2048 |
| maxFragmentInputComponents | 128 |
| maxVertexOutputComponents | 128 |
| maxTextureDimension | 16384 |
| maxPushConstantsSize | 256 bytes |
| maxBoundDescriptorSets | 7 |

---

## Memory

**Heap 0** — 8417 MB, DEVICE_LOCAL

| Type | Heap | Flags |
|------|------|-------|
| 0 | 0 | DEVICE_LOCAL \| HOST_VISIBLE \| HOST_COHERENT |
| 1 | 0 | DEVICE_LOCAL \| HOST_VISIBLE \| HOST_COHERENT \| HOST_CACHED |
| 2 | 0 | DEVICE_LOCAL \| HOST_VISIBLE \| HOST_CACHED |
| 3 | 0 | DEVICE_LOCAL \| LAZILY_ALLOCATED |

> Note: single unified memory (no discrete VRAM); all heaps are device-local.
