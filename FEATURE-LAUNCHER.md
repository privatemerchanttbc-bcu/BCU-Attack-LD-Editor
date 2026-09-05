# Shared launcher

Users start installed features with `BCU New Features.bat`. The `.vbs` version runs it without a console window.

## For patch authors

Add a UTF-8 file named `bcu-features/NNN-name.jvmargs`. Files load in filename order. Use one JVM argument per line; blank lines and lines starting with `#` are ignored.

Example: `bcu-features/200-sample.jvmargs`

```text
"-Dsample.home=!BCU_ROOT!\sample-feature"
"-javaagent:!BCU_ROOT!\sample-feature\sample-agent.jar"
```

`!BCU_ROOT!` is the BCU folder. Quote arguments that contain paths.

- Prefix `100` is used by Attack LD Editor. Choose another prefix for your patch.
- Add your own registration file; do not edit the launcher.
- Do not register the same agent twice or reuse another patch's system properties.
- Test patches together: changing the same BCU classes can cause conflicts.
- On uninstall, remove only your patch's registration.
