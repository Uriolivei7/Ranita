// use an integer for version numbers
version = 5


cloudstream {
    language = "es"
    // All of these properties are optional, you can safely remove them

    //description = "Lorem Ipsum"
    authors = listOf("Ranita")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Anime",
        "OVA",
    )

    //iconUrl = "https://www.google.com/s2/favicons?domain=animeflv.net&sz=%size%"
}
