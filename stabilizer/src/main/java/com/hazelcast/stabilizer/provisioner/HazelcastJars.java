package com.hazelcast.stabilizer.provisioner;

import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.stabilizer.Utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

public class HazelcastJars {

    private final static ILogger log = Logger.getLogger(HazelcastJars.class);
    private final Bash bash;
    private final String versionSpec;

    private File hazelcastJarsDir;

    public HazelcastJars(Bash bash, String versionSpec){
        this.bash = bash;
        this.versionSpec = versionSpec;
    }

    public String getAbsolutePath(){
        return hazelcastJarsDir.getAbsolutePath();
    }

    public void prepare() {
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        hazelcastJarsDir = new File(tmpDir, "hazelcastjars-" + UUID.randomUUID().toString());
        hazelcastJarsDir.mkdirs();

        if (versionSpec.equals("outofthebox")) {
            log.info("Using Hazelcast version-spec: outofthebox");
        } else if (versionSpec.startsWith("path=")) {
            String path = versionSpec.substring(5);
            log.info("Using Hazelcast version-spec: path=" + path);
            File file = new File(path);
            if (!file.exists()) {
                log.severe("Directory :" + path + " does not exist");
                System.exit(1);
            }

            if (!file.isDirectory()) {
                log.severe("File :" + path + " is not a directory");
                System.exit(1);
            }

            bash.bash(format("cp %s/* %s", path, hazelcastJarsDir.getAbsolutePath()));
        } else if (versionSpec.equals("none")) {
            log.info("Using Hazelcast version-spec: none");
            //we don't need to do anything
        } else if (versionSpec.startsWith("maven=")) {
            String version = versionSpec.substring(6);
            log.info("Using Hazelcast version-spec: maven=" + version);
            mavenRetrieve("hazelcast", version);
            mavenRetrieve("hazelcast-client", version);
        } else {
            log.severe("Unrecognized version spec:" + versionSpec);
            System.exit(1);
        }
    }

    private void mavenRetrieve(String artifact, String version) {
        File userhome = new File(System.getProperty("user.home"));
        File repositoryDir = Utils.toFile(userhome, ".m2", "repository");
        File artifactFile = Utils.toFile(repositoryDir, "com", "hazelcast",
                artifact, version, format("%s-%s.jar", artifact, version));
        if (artifactFile.exists()) {
            log.finest("Using artifact: " + artifactFile + " from local maven repository");
            bash.bash(format("cp %s %s", artifactFile.getAbsolutePath(), hazelcastJarsDir.getAbsolutePath()));
        } else {
            log.finest("Artifact: " + artifactFile + " is not found in local maven repository, trying online one");

            String url;
            if (version.endsWith("-SNAPSHOT")) {
                String baseUrl = "https://oss.sonatype.org/content/repositories/snapshots";
                String mavenMetadataUrl = format("%s/com/hazelcast/%s/%s/maven-metadata.xml", baseUrl, artifact, version);
                log.finest("Loading: " + mavenMetadataUrl);
                String mavenMetadata = null;
                try {
                    mavenMetadata = Utils.getText(mavenMetadataUrl);
                } catch (FileNotFoundException e) {
                    log.severe("Failed to load " + artifact + "-" + version + ", because :"
                            + mavenMetadataUrl + " was not found");
                    System.exit(1);
                } catch (IOException e) {
                    log.severe("Could not load:" + mavenMetadataUrl);
                    System.exit(1);
                }

                log.finest(mavenMetadata);
                String timestamp = getTagValue(mavenMetadata, "timestamp");
                String buildnumber = getTagValue(mavenMetadata, "buildNumber");
                String shortVersion = version.replace("-SNAPSHOT", "");
                url = format("%s/com/hazelcast/%s/%s/%s-%s-%s-%s.jar",
                        baseUrl, artifact, version, artifact, shortVersion, timestamp, buildnumber);
            } else {
                String baseUrl = "http://repo1.maven.org/maven2";
                url = format("%s/com/hazelcast/%s/%s/%s-%s.jar", baseUrl, artifact, version, artifact, version);
            }

            bash.bash(format("wget --no-verbose --directory-prefix=%s %s", hazelcastJarsDir.getAbsolutePath(), url));
        }
    }

    private String getTagValue(String mavenMetadata, String tag) {
        final Pattern pattern = Pattern.compile("<" + tag + ">(.+?)</" + tag + ">");
        final Matcher matcher = pattern.matcher(mavenMetadata);

        if (!matcher.find()) {
            throw new RuntimeException("Could not find " + tag + " in:" + mavenMetadata);
        }

        return matcher.group(1);
    }
}