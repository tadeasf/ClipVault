package cz.tadeasf.clipvault

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform