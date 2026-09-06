plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("nativecode_runtime")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
