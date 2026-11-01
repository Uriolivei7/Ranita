// use an integer for version numbers
version = 2


cloudstream {
    language = "es"
    description = "TV en vivo de Guatemala. Canales en vivo de tvguatemalaenvivo.com."
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

    iconUrl = "https://www.tvguatemalaenvivo.com/favicon.ico"
}
