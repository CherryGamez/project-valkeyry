package io.valkeyry.ipaas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "valkeyry.claimcheck")
public class ClaimCheckProperties {

    private boolean enabled = true;
    private long thresholdBytes = 256 * 1024;
    private S3 s3 = new S3();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public long getThresholdBytes() { return thresholdBytes; }
    public void setThresholdBytes(long v) { this.thresholdBytes = v; }
    public S3 getS3() { return s3; }
    public void setS3(S3 s3) { this.s3 = s3; }

    public static class S3 {
        private String endpoint = "";
        private String region = "us-east-1";
        private String bucket = "valkeyry-claimcheck";
        private String accessKey = "";
        private String secretKey = "";
        private boolean pathStyle = true;
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String v) { this.endpoint = v; }
        public String getRegion() { return region; }
        public void setRegion(String v) { this.region = v; }
        public String getBucket() { return bucket; }
        public void setBucket(String v) { this.bucket = v; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String v) { this.accessKey = v; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String v) { this.secretKey = v; }
        public boolean isPathStyle() { return pathStyle; }
        public void setPathStyle(boolean v) { this.pathStyle = v; }
    }
}
