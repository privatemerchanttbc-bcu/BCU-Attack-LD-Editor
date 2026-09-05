# Source files

```text
BCU-Attack-LD-Editor/
  Install-BCU-Attack-LD-Editor.jar   Installer to share with users
  installer-src/                    Installer source code
  payload/                         Compiled feature
  source/                          Feature and integration source files
    manualcontrol/attackform/
      animator/                    LD controls, dragging and preview
      data/                        Attack values and pack storage
      editor/                      Unit Editor integration
      runtime/                     Battle behavior
      transform/                   Connections to BCU code
    manualcontrol/animedit/         Direct Edit integration
    page/anim/                     Preview access
    tests/                         Automated tests
  build-installer.bat               Repackages the installer
  SHA256SUMS.txt                    File checksums
```

## Build the installer

Set `JAVA_HOME` to a JDK 17 installation, then run `build-installer.bat`. It compiles the installer and bundles `payload/attack-ld-editor-agent.jar`.

This does **not** rebuild the feature itself. The supplied feature source files are a subset of the shared Crazy Patch project; rebuilding the feature requires that project's remaining source files, dependencies and build setup.

## Saved attack data

LD values are saved in the unit's pack, not in this feature folder. The main attack uses BCU's unit data; extra attack forms use the pack's `attack_forms/index.json`. Extra animation timelines use `maanim_attack2.txt` through `maanim_attack5.txt` in the animation folder.

The package does not include the original BCU application or your packs and saves.
