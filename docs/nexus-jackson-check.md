# Check Jackson in an Internal Maven Repository

Use this guide to verify that Jackson Databind 2.18.2 is available through your organization's Nexus repository.

> Replace `YOUR_NEXUS_URL` with the base URL of your internal Maven repository. Do not commit credentials or private repository tokens.

## 1. Jackson dependency in pom.xml

Add the version property:

```xml
<properties>
    <jackson.version>2.18.2</jackson.version>
</properties>
```

Use the property in the dependency:

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>${jackson.version}</version>
</dependency>
```

## 2. Check available versions from PowerShell

```powershell
$url = "YOUR_NEXUS_URL/com/fasterxml/jackson/core/jackson-databind/maven-metadata.xml"

[xml]$xml = (Invoke-WebRequest -Uri $url).Content
$xml.metadata.versioning.versions.version
```

## 3. Check whether a specific version is downloadable

Replace the repository URL below with your internal Nexus URL:

```powershell
mvn dependency:get "-Dartifact=com.fasterxml.jackson.core:jackson-databind:2.18.2" "-DremoteRepositories=internal-maven::default::YOUR_NEXUS_URL"
```

For example, the repository URL should end with:

```text
/repository/maven-public
```

## 4. Build the project

After confirming dependency resolution:

```bash
mvn -U clean package
```

## Notes

- A version appearing in Nexus metadata does not by itself guarantee that every required transitive dependency is available.
- A Maven mirror configured in `~/.m2/settings.xml` controls repository routing globally.
- Do not commit internal credentials or secrets to this repository.
