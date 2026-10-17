// use an integer for version numbers
version = 5


cloudstream {
    language = "mx"
    // All of these properties are optional, you can safely remove them

    description = "TV en vivo por país (playlists IPTV publicas de iptv-org). Inspirado en Serbero Play TV."
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
        "Live",
    )

    iconUrl = "https://images.icon-icons.com/935/PNG/512/television-outline_icon-icons.com_73141.png"
}
