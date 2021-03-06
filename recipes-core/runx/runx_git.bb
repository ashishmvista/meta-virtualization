HOMEPAGE = "https://github.com/lf-edge/runx"
SUMMARY = "runx stuff"
DESCRIPTION = "Xen Runtime for OCI"

SRCREV_runx = "f24efd33fb18469e9cfe4d1bfe8e2c90ec8c4e93"
SRC_URI = "\
	  git://github.com/lf-edge/runx;nobranch=1;name=runx \
          https://www.kernel.org/pub/linux/kernel/v5.x/linux-5.4.tar.xz;destsuffix=git/kernel/build \
          file://0001-make-kernel-cross-compilation-tweaks.patch \
          file://0001-make-initrd-cross-install-tweaks.patch \
	  "
SRC_URI[md5sum] = "ce9b2d974d27408a61c53a30d3f98fb9"
SRC_URI[sha256sum] = "bf338980b1670bca287f9994b7441c2361907635879169c64ae78364efc5f491"

LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://LICENSE;md5=945fc9aa694796a6337395cc291ddd8c"

S = "${WORKDIR}/git"
PV = "0.1-git${SRCREV_runx}"

inherit features_check
REQUIRED_DISTRO_FEATURES = "vmsep"

inherit pkgconfig
# for the kernel build
inherit kernel-arch

# we have a busybox bbappend that makes /bin available to the
# sysroot, and hence gets us the target binary that we need
DEPENDS = "busybox go-build"

# for the kernel build phase
DEPENDS += "openssl-native coreutils-native util-linux-native xz-native bc-native"
DEPENDS += "qemu-native bison-native"

RDEPENDS_${PN} += " jq bash"
RDEPENDS_${PN} += " xen-tools-xl go-build socat daemonize"

do_compile() {
    # we'll need this for the initrd later, so lets error if it isn't what
    # we expect (statically linked)
    file ${STAGING_DIR_HOST}/bin/busybox.nosuid
    
    # prep steps to short circuit some of make-kernel's fetching and
    # building.
    mkdir -p ${S}/kernel/build
    mkdir -p ${S}/kernel/src
    cp ${DL_DIR}/linux-4.15.tar.xz ${S}/kernel/build/

    # In the future, we might want to link the extracted kernel source (if
    # we move patches to recipe space, but for now, we need make-kernel to
    # extract a copy and possibly patch it.
    # ln -sf ${WORKDIR}/linux-4.15 ${S}/kernel/src/

    # build the kernel
    echo "[INFO]: runx: building the kernel"

    export KERNEL_CC="${KERNEL_CC}"
    export KERNEL_LD="${KERNEL_LD}"
    export ARCH="${ARCH}"
    export HOSTCC="${BUILD_CC} ${BUILD_CFLAGS} ${BUILD_LDFLAGS}"
    export HOSTCPP="${BUILD_CPP}"
    export CROSS_COMPILE="${CROSS_COMPILE}"
    export build_vars="HOSTCC='$HOSTCC' STRIP='$STRIP' OBJCOPY='$OBJCOPY' ARCH=$ARCH CC='$KERNEL_CC' LD='$KERNEL_LD'"
        
    unset CFLAGS CPPFLAGS CXXFLAGS LDFLAGS MACHINE

    # We want make-kernel, to have the following build lines:
    #    make O=$kernel_builddir HOSTCC="${HOSTCC}" ARCH=$ARCH oldconfig
    #    make -j4 O=$kernel_builddir HOSTCC="${HOSTCC}" STRIP="$STRIP" OBJCOPY="$OBJCOPY" ARCH=$ARCH CC="$KERNEL_CC" LD="$KERNEL_LD" $image
    ${S}/kernel/make-kernel

    # construct the initrd
    echo "[INFO]: runx: constructing the initrd"

    cp ${STAGING_DIR_HOST}/bin/busybox.nosuid ${WORKDIR}/busybox
    export QEMU_USER=`which qemu-${HOST_ARCH}`
    export BUSYBOX="${WORKDIR}/busybox"
    export CROSS_COMPILE="t"
    ${S}/initrd/make-initrd
}

do_install() {
    install -d ${D}${bindir}
    install -m 755 ${S}/runX ${D}${bindir}
    
    install -d ${D}${datadir}/runX
    install -m 755 ${S}/kernel/out/kernel ${D}/${datadir}/runX
    install -m 755 ${S}/initrd/out/initrd ${D}/${datadir}/runX
    install -m 755 ${S}/files/start ${D}/${datadir}/runX
    install -m 755 ${S}/files/create ${D}/${datadir}/runX
    install -m 755 ${S}/files/state ${D}/${datadir}/runX
    install -m 755 ${S}/files/delete ${D}/${datadir}/runX
    install -m 755 ${S}/files/serial_start ${D}/${datadir}/runX


}

deltask compile_ptest_base

FILES_${PN} += "${bindir}/* ${datadir}/runX/*"

INHIBIT_PACKAGE_STRIP = "1"
INSANE_SKIP_${PN} += "ldflags already-stripped"
