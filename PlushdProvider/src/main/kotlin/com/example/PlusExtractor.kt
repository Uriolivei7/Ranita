package com.example

import com.lagradost.cloudstream3.extractors.VidStack

class PelisplusUpnsPro : VidStack() {
    override var mainUrl = "https://pelisplus.upns.pro"
    override var name = "PelisplusUpns"
}
class PelisplusUpnsPro2 : VidStack() {
    override var mainUrl = "https://pelisplus.strp2p.com"
    override var name = "PelisplusStrp2p"
}
class PelisplusUpnsPro3 : VidStack() {
    override var mainUrl = "https://pelisplusto.4meplayer.pro"
    override var name = "Pelisplus4meplayer"
}
class PelisplusRpmstreamLive : VidStack() {
    override var mainUrl = "https://pelisplus.rpmstream.live"
    override var name = "PelisplusRpmstream"
}
