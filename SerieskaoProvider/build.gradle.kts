// use an integer for version numbers
version = 21

cloudstream {
    language = "es"
    // All of these properties are optional, you can safely remove them

    description = "Adaptación SeriesKao"
    authors = listOf("SeriesKao")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Cartoon",
        "Anime"
    )

    //iconUrl = "https://github.com/SaurabhKaperwan/CSX/raw/refs/heads/master/NetflixMirrorProvider/icon.png"
}
