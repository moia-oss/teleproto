package io.moia.protos.teleproto

class VersionNotSupportedException[Version](val version: Version, val supported: Set[Version])
    extends Exception(s"$version is not supported: ${supported.mkString(", ")}")
