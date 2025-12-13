dependencies {
    implementation("com.google.android.material:material:1.4.0")
}

// use an integer for version numbers
version = 1


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

    iconUrl = "https://play-lh.googleusercontent.com/gMB8Eq9g9Uq6CXiOHcqatXpx-6HTexAzcQibTgpqAE756Fnt1Jyyp0j7S3UDKPztkA=w480-h960-rw"
    description = "Cần nhập link Xtream IPTV, tài khoản và mật khẩu để xem"
    requiresResources = true
}
