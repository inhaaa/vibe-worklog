FROM postgres:18.6-bookworm@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc AS pg-build

WORKDIR /build
RUN test "$(dpkg --print-architecture)" = amd64 \
    && sed 's/^deb /deb-src /' /etc/apt/sources.list.d/pgdg.list > /etc/apt/sources.list.d/worklog-pgdg-source.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends build-essential dpkg-dev fakeroot \
    && apt-get download postgresql-common-dev=293.pgdg12+1 \
    && echo '4c8c0531f43150865c0b4ebe2013e86ab87a1e53496391f68eafffd9b0a574dd  postgresql-common-dev_293.pgdg12+1_all.deb' | sha256sum -c - \
    && apt-get install -y --no-install-recommends /build/postgresql-common-dev_293.pgdg12+1_all.deb \
    && apt-get source --download-only postgresql-18=18.6-1.pgdg12+2 \
    && echo '76fda1b633b578b0469d00418936e267a658bf1055deedd4fbb515a2c8689eec  postgresql-18_18.6-1.pgdg12+2.dsc' | sha256sum -c - \
    && echo '555610c24d53e4316da5b7d3fc25c279d96856d5e0e23ee308c328c5fa881d9f  postgresql-18_18.6.orig.tar.bz2' | sha256sum -c - \
    && echo '264d4fa9cccc317b5f30dcccafe748d8d008f7e5c3fdff48a177f1bf273204d4  postgresql-18_18.6-1.pgdg12+2.debian.tar.xz' | sha256sum -c - \
    && apt-get -Ppkg.postgresql.nollvm,pkg.postgresql.nolibpkgs,nodoc,nocheck build-dep -y --no-install-recommends postgresql-18=18.6-1.pgdg12+2 \
    && dpkg-source -x postgresql-18_18.6-1.pgdg12+2.dsc source

WORKDIR /build/source
# Preserve PGDG paths, hardening and storage ABI; remove every runtime libxml consumer.
RUN printf '\nCONFIGURE_FLAGS := $(filter-out --with-libxml --with-libxslt --with-llvm,$(CONFIGURE_FLAGS)) --without-libxml --without-libxslt --without-llvm\n' >> debian/rules \
    && printf 'postgresql-18 (18.6-1.pgdg12+2+worklog1) bookworm-pgdg; urgency=medium\n\n  * WorkLog local build: disable XML, XSLT and LLVM/JIT runtime consumers.\n\n -- WorkLog local build <maintainer@example.invalid>  Tue, 27 Oct 2026 00:00:00 +0000\n\n' > /tmp/worklog-changelog \
    && cat debian/changelog >> /tmp/worklog-changelog \
    && mv /tmp/worklog-changelog debian/changelog \
    && DEB_BUILD_PROFILES='pkg.postgresql.nollvm pkg.postgresql.nolibpkgs nodoc nocheck' \
       DEB_BUILD_OPTIONS='nocheck parallel=2' dpkg-buildpackage -B -us -uc -j2 \
    && chown -R postgres:postgres /build/source \
    && gosu postgres make -C /build/source/build/src/test/regress check

FROM postgres:18.6-bookworm@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc AS runtime

ENV PG_VERSION=18.6-1.pgdg12+2+worklog1
LABEL io.worklog.postgresql.source-version="18.6-1.pgdg12+2" \
      io.worklog.postgresql.disabled-features="libxml,libxslt,llvm" \
      io.worklog.postgresql.package-version="18.6-1.pgdg12+2+worklog1"

COPY --from=pg-build /build/postgresql-18_18.6-1.pgdg12+2+worklog1_amd64.deb /tmp/worklog-pg/
COPY --from=pg-build /build/postgresql-client-18_18.6-1.pgdg12+2+worklog1_amd64.deb /tmp/worklog-pg/

# Use complete packages so dependency metadata, clients and contrib match the rebuilt server.
RUN apt-get update \
    && apt-get install -y --no-install-recommends /tmp/worklog-pg/*.deb \
        libssl3=3.0.22-1~deb12u2 openssl=3.0.22-1~deb12u2 \
    && apt-get purge -y --auto-remove \
        postgresql-18-jit libllvm19 libxml2 libxslt1.1 \
        gnupg gpg gpgsm gpg-agent gpgconf gnupg-utils dirmngr libsqlite3-0 \
    && test "$(dpkg-query -W -f='${Version}' postgresql-18)" = 18.6-1.pgdg12+2+worklog1 \
    && test "$(dpkg-query -W -f='${Version}' postgresql-client-18)" = 18.6-1.pgdg12+2+worklog1 \
    && test ! -e /usr/lib/x86_64-linux-gnu/libxml2.so.2 \
    && test ! -e /usr/lib/postgresql/18/lib/llvmjit.so \
    && test ! -e /usr/lib/postgresql/18/lib/pgxml.so \
    && test ! -e /usr/share/postgresql/18/extension/xml2.control \
    && printf 'WorkLog local PostgreSQL build\nSource: PGDG 18.6-1.pgdg12+2\nPackage: 18.6-1.pgdg12+2+worklog1\nSource URL: https://apt.postgresql.org/pub/repos/apt/pool/main/p/postgresql-18/\nDSC SHA256: 76fda1b633b578b0469d00418936e267a658bf1055deedd4fbb515a2c8689eec\nOriginal SHA256: 555610c24d53e4316da5b7d3fc25c279d96856d5e0e23ee308c328c5fa881d9f\nDebian SHA256: 264d4fa9cccc317b5f30dcccafe748d8d008f7e5c3fdff48a177f1bf273204d4\nOptions: --without-libxml --without-libxslt --without-llvm\nPGDG paths, hardening and storage format preserved. LLVM/JIT unavailable.\nOriginal PostgreSQL and Debian copyright/license notices retained beside this file.\n' > /usr/share/doc/postgresql-18/worklog-no-xml-build.txt \
    && rm -f /tmp/worklog-pg/*.deb \
    && rm -rf /var/lib/apt/lists/*

# The supported local images are linux/amd64; verify the vendor binary before use.
ADD https://github.com/tianon/gosu/releases/download/1.20/gosu-amd64 /usr/local/bin/gosu
RUN test "$(dpkg --print-architecture)" = amd64 \
    && echo 'b1892636c281590f34bc63c1118f058cd9de66c4ea463772e3909672d8b302ac  /usr/local/bin/gosu' | sha256sum -c - \
    && chmod 0755 /usr/local/bin/gosu \
    && gosu --version
