# IMet XI

Java 17 multi-module Maven workspace. Open the root `pom.xml` in IntelliJ as a new project and select JDK 17.

## Modules

- `plugin-api`: the single source of plugin and transport contracts. No app or hardware dependencies.
- `app`: Swing application, discovery, serial transport, and runtime plugin loading. Depends on the API, never on the X4 implementation.
- `plugins/x4`: separate X4 plugin JAR and lexicon. Compiles against the API and jSSC supplied by the host at runtime.
- `distribution`: stages the runnable app, external plugin, lexicon, launch scripts, ZIP, and optional Windows installer.

The app's shaded JAR includes the API and runtime libraries. The plugin JAR contains only its implementation. The existing parent-first URLClassLoader resolves the shared API from the host. Maven modules do not introduce Java JPMS modules.

## Build and run

```sh
mvn clean verify
./distribution/target/xi-work/run.sh
```

Windows: run `distribution\target\xi-work\run.bat`.

The portable ZIP is `distribution/target/xi-work-0.1.0.zip`; it requires Java 17 or later. App and plugin remain separate JARs in the ZIP. Generated JARs are not checked into Git.

In IntelliJ, first run the root Maven `package` lifecycle, then use the shared **IMet XI** run configuration. It runs app classes with the staged distribution as its working directory. After changing plugin code or lexicons, run root `mvn package` again to refresh the external plugin/resources before launching. Neither IDE module dependencies nor the application classpath include `x4-plugin`.

To build only one component and its prerequisites:

```sh
mvn -pl app -am package
mvn -pl plugins/x4 -am package
```

These commands do not refresh the assembled distribution; use the root build for that.

## Windows installer

On Windows with JDK 17+, Maven, and the existing jpackage/WiX toolchain installed:

```bat
build-installer.bat
```

Equivalent: `mvn clean package -Pinstaller`. Output is under `distribution/target/installer`. The installer profile belongs exclusively to the distribution module. Installer JVM properties point both existing resource lookup mechanisms at the packaged plugins and lexicons.

## Structural migration

Application and plugin behavior is retained, including temporary X4-specific application logic and the plugin's existing serial access. The interface package and implementation class name stay unchanged for the existing loader. The X4 lexicon still names `X4Plugin.jar`.

The old repositories are migration inputs, not build dependencies. API changes now compile against both consumers in the same reactor. Independently distributed older plugin binaries still require API compatibility.

`mvn verify` also checks the packaged app/plugin boundary and a simulated transport handshake. It does not open hardware ports or a GUI. Windows MSI creation must be validated on Windows.
