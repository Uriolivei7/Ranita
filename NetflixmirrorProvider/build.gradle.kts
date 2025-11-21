// use an integer for version numbers
version = 35

cloudstream {
    //language = "en"
    // All of these properties are optional, you can safely remove them

    description = "Netflix y PrimeVideo Contenido en Múltiples Lenguajes"
    authors = listOf("Ranita")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama",
        "Anime"
    )

    //iconUrl = "https://github.com/SaurabhKaperwan/CSX/raw/refs/heads/master/NetflixMirrorProvider/icon.png"
}
