# Changelog

All notable changes to this module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This module is internal tooling and is never published; versions below track the
library's version.

## [Unreleased]

### Added

- `holdfast` CLI with `snapshot inspect` (schema version, states, unencodable
  names, redaction-aware rendered values), `snapshot validate` (decode check
  with offset-bearing failures, canonicality note, `--strict`), `snapshot diff`
  (schema drift, added/removed state names, line-diff of rendered values), and
  `doctor` (build/runtime environment). All parsing goes through holdfast's
  public `StoreSnapshot` API; the wire format is never reimplemented.
