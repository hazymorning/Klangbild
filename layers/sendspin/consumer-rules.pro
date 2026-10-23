# cryptography-kotlin reaches Bouncy Castle through Java's security API, which makes each
# algorithm from its class name. These are the ones the Noise session asks for: X25519,
# ChaCha20-Poly1305, SHA-256 and HMAC-SHA256. ChaCha and SHA256 keep their names because their
# mappings name the algorithms after them.
-keep class org.bouncycastle.jcajce.provider.asymmetric.EdEC$Mappings { <init>(); }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.KeyFactorySpi$X25519 { <init>(); }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.KeyAgreementSpi$XDH { <init>(); }
-keepnames class org.bouncycastle.jcajce.provider.symmetric.ChaCha
-keep class org.bouncycastle.jcajce.provider.symmetric.ChaCha$Mappings { <init>(); }
-keep class org.bouncycastle.jcajce.provider.symmetric.ChaCha$BaseCC20P1305 { <init>(); }
-keepnames class org.bouncycastle.jcajce.provider.digest.SHA256
-keep class org.bouncycastle.jcajce.provider.digest.SHA256$Mappings { <init>(); }
-keep class org.bouncycastle.jcajce.provider.digest.SHA256$Digest { <init>(); }
-keep class org.bouncycastle.jcajce.provider.digest.SHA256$HashMac { <init>(); }
