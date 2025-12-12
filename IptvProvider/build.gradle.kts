dependencies {
    implementation("com.google.android.material:material:1.4.0")
}

// use an integer for version numbers
version = 9


android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}


cloudstream {
    // All of these properties are optional, you can safely remove them

    // description = "Lorem Ipsum"
    authors = listOf("anhdaden")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://www.shutterstock.com/image-vector/iptv-vector-line-icon-ip-260nw-1841427610.jpg"
    description = "Cần nhập link IPTV để xem"
    requiresResources = true
}
