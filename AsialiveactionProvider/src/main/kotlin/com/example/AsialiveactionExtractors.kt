package com.example

import com.lagradost.cloudstream3.extractors.VidStack

class MomoEzplayer : VidStack() {
    override var name = "MomoEzplayer"
    override var mainUrl = "https://momo.ezplayer.me"
    override var requiresReferer = true
}

class SuUpnOne : VidStack() {
    override var name = "SuUpnOne"
    override var mainUrl = "https://su.upn.one"
    override var requiresReferer = true
}

class MoaRpmvip : VidStack() {
    override var name = "MoaRpmvip"
    override var mainUrl = "https://moa.rpmvip.com"
    override var requiresReferer = true
}

class Bysejikuar : VidStack() {
    override var name = "Bysejikuar"
    override var mainUrl = "https://bysejikuar.com"
    override var requiresReferer = true
}
