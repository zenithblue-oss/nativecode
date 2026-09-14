plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("nativecode_android_dev")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
