package me.phie.tawc.launcher

import me.phie.tawc.install.Installation

/**
 * Pre-flight check for GUI launches. Electron apps (Code - OSS, VSCodium,
 * Element, …) link against a handful of system libraries that the minimal
 * rootfs images do not ship, and the failure is opaque: the program dies
 * within milliseconds with
 *
 *   error while loading shared libraries: libnspr4.so: cannot open shared
 *   object file
 *
 * on a stderr [EntryLauncher] sends to /dev/null, so all the user sees is a
 * tap that does nothing. Finding out which package carries `libnspr4.so`
 * needs `pacman -F`/`apt-file`, which is a lot to ask for "the app I just
 * installed does not start".
 *
 * So: ask the guest which of the binary's dependencies are missing, map the
 * ones we know onto a package per package-manager family, and let the user
 * install them or launch anyway.
 *
 * Everything here except [probeScript] is pure — the parsing and the
 * mapping are what break silently, so they are unit-tested
 * (`ElectronDepsTest`).
 */
internal object ElectronDeps {

    /** Sonames that only the Electron/Chromium family pulls in. */
    private val ELECTRON_MARKERS = listOf("libnspr4.so", "libnss3.so")

    enum class Family { PACMAN, APT, XBPS }

    /** One package name per family; the key is the missing soname. */
    private data class Names(val pacman: String, val apt: String, val xbps: String) {
        fun forFamily(family: Family): String = when (family) {
            Family.PACMAN -> pacman
            Family.APT -> apt
            Family.XBPS -> xbps
        }
    }

    private val PACKAGES: Map<String, Names> = mapOf(
        "libnspr4.so" to Names("nspr", "libnspr4", "nspr"),
        "libnss3.so" to Names("nss", "libnss3", "nss"),
        "libnssutil3.so" to Names("nss", "libnss3", "nss"),
        "libsmime3.so" to Names("nss", "libnss3", "nss"),
        "libssl3.so" to Names("nss", "libnss3", "nss"),
        "libxss.so.1" to Names("libxss", "libxss1", "libXScrnSaver"),
        "libXtst.so.6" to Names("libxtst", "libxtst6", "libXtst"),
        "libgtk-3.so.0" to Names("gtk3", "libgtk-3-0", "gtk+3"),
        "libsecret-1.so.0" to Names("libsecret", "libsecret-1-0", "libsecret"),
        "libasound.so.2" to Names("alsa-lib", "libasound2", "alsa-lib"),
        "libnotify.so.4" to Names("libnotify", "libnotify4", "libnotify"),
        "libcups.so.2" to Names("libcups", "libcups2", "libcups"),
        "libatk-1.0.so.0" to Names("atk", "libatk1.0-0", "atk"),
        "libatspi.so.0" to Names("at-spi2-core", "libatspi2.0-0", "at-spi2-core"),
        "libgbm.so.1" to Names("mesa", "libgbm1", "mesa"),
        "libdrm.so.2" to Names("libdrm", "libdrm2", "libdrm"),
        "libxkbcommon.so.0" to Names("libxkbcommon", "libxkbcommon0", "libxkbcommon"),
        "libXcomposite.so.1" to Names("libxcomposite", "libxcomposite1", "libXcomposite"),
        "libXdamage.so.1" to Names("libxdamage", "libxdamage1", "libXdamage"),
        "libXrandr.so.2" to Names("libxrandr", "libxrandr2", "libXrandr"),
        "libpango-1.0.so.0" to Names("pango", "libpango-1.0-0", "pango"),
        "libcairo.so.2" to Names("cairo", "libcairo2", "cairo"),
        "libnss-mdns" to Names("nss-mdns", "libnss-mdns", "nss-mdns"),
    )

    /** What the probe found. */
    data class Report(
        /** True when the binary links the Electron/NSS marker pair. */
        val electron: Boolean,
        /** Sonames `ldd` could not resolve, in output order. */
        val missing: List<String>,
        /** Packages installable on this distro to fix [missing]. */
        val packages: List<String>,
        /** Sonames we could not map to a package (report them verbatim). */
        val unknown: List<String>,
        /** Ready-to-paste install command, or null when nothing is mapped. */
        val command: String?,
    ) {
        val worthWarning: Boolean get() = missing.isNotEmpty()
    }

    fun familyOf(distroKey: String?): Family? = when (distroKey) {
        Installation.DISTRO_ARCH,
        Installation.DISTRO_MANJARO,
        -> Family.PACMAN

        Installation.DISTRO_DEBIAN_SID,
        Installation.DISTRO_UBUNTU,
        -> Family.APT

        Installation.DISTRO_VOID -> Family.XBPS
        else -> null
    }

    fun installCommand(family: Family, packages: List<String>): String = when (family) {
        Family.PACMAN -> "pacman -S --needed ${packages.joinToString(" ")}"
        Family.APT -> "apt-get install -y ${packages.joinToString(" ")}"
        Family.XBPS -> "xbps-install -Sy ${packages.joinToString(" ")}"
    }

    /** Soname of an `ldd` line, or null for lines that carry none. */
    private fun sonameOf(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("not a dynamic executable") ||
            trimmed.startsWith("statically linked") ||
            trimmed.startsWith("cannot find the shared object") ||
            trimmed.contains("=> not found")
        ) {
            // "libfoo.so => not found" keeps its soname; the others do not.
            return if ("=>" in trimmed) trimmed.substringBefore("=>").trim().ifEmpty { null } else null
        }
        return if ("=>" in trimmed) {
            trimmed.substringBefore("=>").trim().ifEmpty { null }
        } else {
            // No mapping: the loader line is an absolute path, the vdso line
            // a bare soname. Report sonames either way.
            trimmed.substringBefore(" ").trim().substringAfterLast('/').ifEmpty { null }
        }
    }

    /** Dependency sonames a guest `ldd` run reported. */
    fun dependencies(lddOutput: String): List<String> =
        lddOutput.lineSequence().mapNotNull(::sonameOf).distinct().toList()

    /** Sonames that reported as `not found`. */
    fun missingLibs(lddOutput: String): List<String> =
        lddOutput.lineSequence()
            .filter { it.contains("not found") }
            .mapNotNull(::sonameOf)
            .distinct()
            .toList()

    fun looksLikeElectron(deps: List<String>): Boolean =
        deps.any { dep -> ELECTRON_MARKERS.any { dep.startsWith(it) } }

    fun report(lddOutput: String, distroKey: String?): Report {
        val deps = dependencies(lddOutput)
        val missing = missingLibs(lddOutput)
        val family = familyOf(distroKey)
        val packages = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        for (soname in missing) {
            val names = PACKAGES.entries.firstOrNull { soname.startsWith(it.key) }?.value
            if (names == null || family == null) unknown += soname else packages += names.forFamily(family)
        }
        return Report(
            electron = looksLikeElectron(deps),
            missing = missing,
            packages = packages.distinct().sorted(),
            unknown = unknown,
            command = family?.let { installCommand(it, packages.distinct().sorted()) }
                ?.takeIf { packages.isNotEmpty() },
        )
    }

    /**
     * The program to hand `ldd`: `.desktop` Exec lines may start with
     * `env`, carry `VAR=value` pairs, and end in arguments
     * (`env BAMF_DESKTOP_FILE_HINT=x /usr/bin/codium %U`, or a quoted
     * path).
     */
    fun executableOf(exec: String): String {
        val tokens = exec.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val first = tokens.firstOrNull { token ->
            token != "env" && !token.contains('=')
        } ?: tokens.firstOrNull().orEmpty()
        return first.removeSurrounding("\"").removeSurrounding("'")
    }

    /**
     * Guest script that prints `ldd` for the entry's binary. Runs with the
     * rootfs env, so `command -v` sees the same PATH the launch will.
     */
    fun probeScript(exec: String): String {
        val executable = executableOf(exec).replace("'", "'\\''")
        return """
            set -u
            binary='$executable'
            case "${'$'}binary" in
                /*) ;;
                *) resolved="${'$'}(command -v "${'$'}binary" 2>/dev/null || true)"
                   [ -n "${'$'}resolved" ] && binary="${'$'}resolved" ;;
            esac
            if [ ! -e "${'$'}binary" ]; then
                echo "tawc-probe: no such binary: ${'$'}binary"
                exit 0
            fi
            ldd "${'$'}binary" 2>&1 || true
        """.trimIndent()
    }
}
