// use an integer for version numbers
version = 1


cloudstream {
    language = "mx"
    // All of these properties are optional, you can safely remove them

    description = "Re:ANIME - Animes con audio dual JA/EN y subtítulos externos"
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
        "AnimeMovie",
    )

    //iconUrl = "https://www.google.com/s2/favicons?domain=https://reanime.to&sz=%size%"
}
