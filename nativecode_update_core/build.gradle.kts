plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("nativecode_update_core")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
