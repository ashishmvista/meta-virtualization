HOMEPAGE = "http://www.docker.com"
SUMMARY = "Linux container runtime"
DESCRIPTION = "Linux container runtime \
 Docker complements kernel namespacing with a high-level API which \
 operates at the process level. It runs unix processes with strong \
 guarantees of isolation and repeatability across servers. \
 . \
 Docker is a great building block for automating distributed systems: \
 large-scale web deployments, database clusters, continuous deployment \
 systems, private PaaS, service-oriented architectures, etc. \
 . \
 This package contains the daemon and client. Using docker.io is \
 officially supported on x86_64 and arm (32-bit) hosts. \
 Other architectures are considered experimental. \
 . \
 Also, note that kernel version 3.10 or above is required for proper \
 operation of the daemon process, and that any lower versions may have \
 subtle and/or glaring issues. \
 "

SRCREV_docker = "f5e591e815841e04e910223fb63f1ef7689ae153"
SRCREV_libnetwork = "4725f2163fb214a6312f3beae5991f838ec36326"
SRC_URI = "\
	git://github.com/docker/docker-ce.git;branch=18.09;name=docker \
	git://github.com/docker/libnetwork.git;branch=bump_18.09;name=libnetwork;destsuffix=git/libnetwork \
	file://docker.init \
	"

# Apache-2.0 for docker
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://src/import/components/engine/LICENSE;md5=9740d093a080530b5c5c6573df9af45a"

GO_IMPORT = "import"

S = "${WORKDIR}/git"

DOCKER_VERSION = "18.09.3-ce"
PV = "${DOCKER_VERSION}+git${SRCREV_docker}"

DEPENDS = " \
    go-cli \
    go-pty \
    go-context \
    go-mux \
    go-patricia \
    go-logrus \
    go-fsnotify \
    go-dbus \
    go-capability \
    go-systemd \
    btrfs-tools \
    sqlite3 \
    go-distribution \
    compose-file \
    go-connections \
    notary \
    grpc-go \
    libtool-native \
    libtool \
    "

PACKAGES =+ "${PN}-contrib"

DEPENDS_append_class-target = " lvm2"
RDEPENDS_${PN} = "util-linux util-linux-unshare iptables \
                  ${@bb.utils.contains('DISTRO_FEATURES', 'aufs', 'aufs-util', '', d)} \
                  ${@bb.utils.contains('DISTRO_FEATURES', 'systemd', '', 'cgroup-lite', d)} \
                 "
RDEPENDS_${PN} += "virtual/containerd virtual/runc"

RRECOMMENDS_${PN} = "kernel-module-dm-thin-pool kernel-module-nf-nat"
RSUGGESTS_${PN} = "lxc rt-tests"
DOCKER_PKG="github.com/docker/docker"

inherit systemd update-rc.d
inherit go
inherit goarch
inherit pkgconfig

do_configure[noexec] = "1"

do_compile() {
	# Set GOPATH. See 'PACKAGERS.md'. Don't rely on
	# docker to download its dependencies but rather
	# use dependencies packaged independently.
	cd ${S}/src/import
	rm -rf .gopath
	mkdir -p .gopath/src/"$(dirname "${DOCKER_PKG}")"
	ln -sf ../../../../components/engine/ .gopath/src/"${DOCKER_PKG}"

	mkdir -p .gopath/src/github.com/docker
	ln -sf ${WORKDIR}/git/libnetwork .gopath/src/github.com/docker/libnetwork
	ln -sf ${S}/src/import/components/cli .gopath/src/github.com/docker/cli

	export GOPATH="${S}/src/import/.gopath:${S}/src/import/vendor:${STAGING_DIR_TARGET}/${prefix}/local/go"
	export GOROOT="${STAGING_DIR_NATIVE}/${nonarch_libdir}/${HOST_SYS}/go"

	# Pass the needed cflags/ldflags so that cgo
	# can find the needed headers files and libraries
	export GOARCH=${TARGET_GOARCH}
	export CGO_ENABLED="1"
	export CGO_CFLAGS="${CFLAGS} --sysroot=${STAGING_DIR_TARGET}"
	export CGO_LDFLAGS="${LDFLAGS} --sysroot=${STAGING_DIR_TARGET}"
	# in order to exclude devicemapper and btrfs - https://github.com/docker/docker/issues/14056
	export DOCKER_BUILDTAGS='exclude_graphdriver_btrfs exclude_graphdriver_devicemapper'

	export DISABLE_WARN_OUTSIDE_CONTAINER=1

	cd ${S}/src/import/components/engine

	# this is the unsupported build structure
	# that doesn't rely on an existing docker
	# to build this:
	VERSION="${DOCKER_VERSION}" DOCKER_GITCOMMIT="${SRCREV_docker}" ./hack/make.sh dynbinary

	# build the proxy
	cd ${S}/src/import/.gopath/src/github.com/docker/libnetwork
	oe_runmake cross-local

        # build the cli
	cd ${S}/src/import/components/cli
	export CFLAGS=""
	export LDFLAGS=""
	export DOCKER_VERSION=${DOCKER_VERSION}
	VERSION="${DOCKER_VERSION}" DOCKER_GITCOMMIT="${SRCREV_docker}" make dynbinary
}

SYSTEMD_PACKAGES = "${@bb.utils.contains('DISTRO_FEATURES','systemd','${PN}','',d)}"
SYSTEMD_SERVICE_${PN} = "${@bb.utils.contains('DISTRO_FEATURES','systemd','docker.service','',d)}"
SYSTEMD_AUTO_ENABLE_${PN} = "enable"

INITSCRIPT_PACKAGES += "${@bb.utils.contains('DISTRO_FEATURES','sysvinit','${PN}','',d)}"
INITSCRIPT_NAME_${PN} = "${@bb.utils.contains('DISTRO_FEATURES','sysvinit','docker.init','',d)}"
INITSCRIPT_PARAMS_${PN} = "defaults"

do_install() {
	mkdir -p ${D}/${bindir}
	cp ${S}/src/import/components/cli/build/docker ${D}/${bindir}/docker
	cp ${S}/src/import/components/engine/bundles/latest/dynbinary-daemon/dockerd ${D}/${bindir}/dockerd
	cp ${WORKDIR}/git/libnetwork/bin/docker-proxy* ${D}/${bindir}/docker-proxy

	if ${@bb.utils.contains('DISTRO_FEATURES','systemd','true','false',d)}; then
		install -d ${D}${systemd_unitdir}/system
		install -m 644 ${S}/src/import/components/engine/contrib/init/systemd/docker.* ${D}/${systemd_unitdir}/system
		# replaces one copied from above with one that uses the local registry for a mirror
		install -m 644 ${S}/src/import/components/engine/contrib/init/systemd/docker.service ${D}/${systemd_unitdir}/system
	else
		install -d ${D}${sysconfdir}/init.d
		install -m 0755 ${WORKDIR}/docker.init ${D}${sysconfdir}/init.d/docker.init
	fi
	# TLS key that docker creates at run-time if not found is what resides here
	install -d ${D}${sysconfdir}
	ln -s ..${localstatedir}/run/docker ${D}${sysconfdir}/docker

	mkdir -p ${D}${datadir}/docker/
	install -m 0755 ${S}/src/import/components/engine/contrib/check-config.sh ${D}${datadir}/docker/
}

inherit useradd
USERADD_PACKAGES = "${PN}"
GROUPADD_PARAM_${PN} = "-r docker"

FILES_${PN} += "${systemd_unitdir}/system/* ${sysconfdir}/docker"

FILES_${PN}-contrib += "${datadir}/docker/check-config.sh"
RDEPENDS_${PN}-contrib += "bash"

# DO NOT STRIP docker
INHIBIT_PACKAGE_STRIP = "1"
INSANE_SKIP_${PN} += "ldflags"