# AccessCard

NFC access-card wallet for DerpFest, with a launcher app and Quick Settings
card picker.

- NFC-A UID / ATQA / SAK reading and emulation.
- Multiple cards, custom appearance, notes and search.
- Encrypted backups and opt-in diagnostic export.

## Build

Requires the matching [NFC stack changes](https://github.com/Edward-Projects/android_packages_modules_Nfc/tree/accesscard-17).

Include in the product configuration:

```makefile
$(call inherit-product, packages/apps/AccessCard/config/accesscard.mk)
```

Select a DerpFest build target, then run from the source root:

```sh
m AccessCard
```

Emulation requires device and controller qualification. Encrypted MIFARE
Classic sectors are not copied.

## License

[Apache 2.0](LICENSE)
