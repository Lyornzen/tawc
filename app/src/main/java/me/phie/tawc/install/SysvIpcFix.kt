package me.phie.tawc.install

import me.phie.tawc.install.distro.Distro

/**
 * AUR builds die on Android before they start: kernels ship without
 * System V IPC (`CONFIG_SYSVIPC` is off), so `msgget()` returns ENOSYS and
 * fakeroot's `faked` cannot open its message channel —
 *
 *   fakeroot, while creating message channels: Function not implemented
 *
 * makepkg runs fakeroot for the `package()` step, so every `makepkg -si`
 * stops there. Nothing above it is broken: the rootfs, pacman and the
 * compiler are fine, and tawcroot's own seccomp filter allows everything
 * it does not explicitly need to trap (`tawcroot/src/filter.c`, "default
 * → ALLOW"), so the syscall is missing from the kernel, not filtered.
 *
 * fakeroot's configure can move that one channel onto a TCP socket
 * (`--with-ipc=tcp`), which is exactly what the AUR's `fakeroot-tcp`
 * package does. This is the same recipe run in the guest on request: build
 * fakeroot from its checksummed upstream tarball and install it under
 * `/usr/local`, which sits ahead of `/usr/bin` on the rootfs PATH
 * ([RootfsEnv]), so the distro's own — and on Android, unusable — fakeroot
 * package keeps its files and pacman keeps owning them.
 *
 * Everything here is text: the scripts run inside the guest, and the
 * app-side caller ([buildAurBuildRow]) only renders their result. Kept as
 * an object of pure builders so the recipe is unit-testable without a
 * device.
 *
 * Not solved here: unrelated SysV IPC users (`shmget`/`semget`). Emulating
 * those is a tawcroot feature; plans/x86-fex-glibc.md defers it until a
 * real workload needs it. fakeroot needs the message-queue half only.
 */
internal object SysvIpcFix {

    const val FAKEROOT_VERSION = "1.37.1.1"

    /**
     * Upstream source, pinned by digest. The digest — not the host — is
     * what makes this safe, so the guest may fall back to a closer mirror
     * without weakening the check.
     */
    val TARBALL_URLS = listOf(
        "https://deb.debian.org/debian/pool/main/f/fakeroot/fakeroot_$FAKEROOT_VERSION.orig.tar.gz",
        "https://mirror.nju.edu.cn/debian/pool/main/f/fakeroot/fakeroot_$FAKEROOT_VERSION.orig.tar.gz",
    )

    const val TARBALL_SHA256 = "86b0b75bf319ca42e525c098675b6ed10a06b76e69ec9ccf20ef5e03883b3a14"

    /** Install prefix: ahead of `/usr/bin` in the rootfs PATH. */
    const val PREFIX = "/usr/local"

    /** Written only after the TCP build answered a round trip. */
    const val MARKER = "$PREFIX/share/tawc/fakeroot-tcp"

    /** Status tokens [statusScript] prints; parsed by [parseStatus]. */
    const val STATUS_TCP = "tcp"
    const val STATUS_OK = "ok"
    const val STATUS_BROKEN = "broken"
    const val STATUS_MISSING = "missing"

    /** What the guest reported about fakeroot. */
    enum class Status { TCP, OK, BROKEN, MISSING, UNKNOWN }

    fun parseStatus(output: String): Status = when (output.lineSequence().lastOrNull { it.isNotBlank() }?.trim()) {
        STATUS_TCP -> Status.TCP
        STATUS_OK -> Status.OK
        STATUS_BROKEN -> Status.BROKEN
        STATUS_MISSING -> Status.MISSING
        else -> Status.UNKNOWN
    }

    /**
     * Build prerequisites for [distro]'s package manager, or `null` when we
     * do not know that family. `curl` rides along because the fetch below
     * needs one of curl/wget and the minimal images ship neither.
     */
    fun prerequisites(distro: Distro?): String? = when (distro?.key) {
        Installation.DISTRO_ARCH,
        Installation.DISTRO_MANJARO,
        -> "pacman -S --needed --noconfirm base-devel curl"

        Installation.DISTRO_DEBIAN_SID,
        Installation.DISTRO_UBUNTU,
        -> "apt-get install -y --no-install-recommends build-essential curl"
        Installation.DISTRO_VOID -> "xbps-install -Sy base-devel curl"
        else -> null
    }

    /**
     * Reports the state without changing anything. The stock fakeroot is
     * probed by running it: a build without working IPC exits nonzero with
     * the message-channel error, which is the condition we are fixing.
     */
    fun statusScript(): String = """
        set -u
        if [ -x "$PREFIX/bin/fakeroot" ] && "$PREFIX/bin/fakeroot" -- true >/dev/null 2>&1; then
            echo "$STATUS_TCP"
        elif command -v fakeroot >/dev/null 2>&1; then
            if fakeroot -- true >/dev/null 2>&1; then echo "$STATUS_OK"; else echo "$STATUS_BROKEN"; fi
        else
            echo "$STATUS_MISSING"
        fi
    """.trimIndent()

    /**
     * Builds and installs the TCP-IPC fakeroot. Idempotent: an already
     * working setup (ours or the distro's) exits without touching
     * anything, so the row can be tapped twice.
     */
    fun setupScript(distro: Distro?): String? {
        val prerequisites = prerequisites(distro) ?: return null
        val urls = TARBALL_URLS.joinToString(" ") { "'$it'" }
        return """
            set -eu
            export PATH="$PREFIX/sbin:$PREFIX/bin:${'$'}PATH"

            if [ -x "$PREFIX/bin/fakeroot" ] && "$PREFIX/bin/fakeroot" -- true >/dev/null 2>&1; then
                echo "==> fakeroot (TCP IPC) is already installed"
                exit 0
            fi
            if command -v fakeroot >/dev/null 2>&1 && fakeroot -- true >/dev/null 2>&1; then
                echo "==> this distro's fakeroot works here; nothing to do"
                exit 0
            fi

            echo "==> installing build prerequisites ($prerequisites)"
            $prerequisites

            work=${'$'}(mktemp -d /tmp/tawc-fakeroot-tcp.XXXXXX)
            cd "${'$'}work"

            echo "==> fetching fakeroot $FAKEROOT_VERSION"
            fetched=0
            for url in $urls; do
                if command -v curl >/dev/null 2>&1; then
                    curl -fsSL -o fakeroot.tar.gz "${'$'}url" && fetched=1 && break
                elif command -v wget >/dev/null 2>&1; then
                    wget -q -O fakeroot.tar.gz "${'$'}url" && fetched=1 && break
                else
                    echo "ERROR: neither curl nor wget is available" >&2
                    exit 1
                fi
            done
            [ "${'$'}fetched" = 1 ] || { echo "ERROR: could not download the fakeroot source" >&2; exit 1; }

            echo "$TARBALL_SHA256  fakeroot.tar.gz" | sha256sum -c -

            tar xf fakeroot.tar.gz
            cd "fakeroot-$FAKEROOT_VERSION"

            echo "==> configuring (--with-ipc=tcp)"
            ./configure --with-ipc=tcp --prefix=$PREFIX --libdir=$PREFIX/lib

            echo "==> building"
            make -j"${'$'}(nproc)"

            echo "==> installing into $PREFIX"
            make install

            mkdir -p "${'$'}(dirname $MARKER)"
            : > "$MARKER"

            echo "==> done: ${'$'}("$PREFIX/bin/fakeroot" -- echo 'fakeroot works' 2>&1)"
            echo "==> 'makepkg -si' can now run AUR builds"
        """.trimIndent()
    }
}
