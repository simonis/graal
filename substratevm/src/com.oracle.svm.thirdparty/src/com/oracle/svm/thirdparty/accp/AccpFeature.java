package com.oracle.svm.thirdparty.accp;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RecomputeFieldValue.Kind;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.NativeLibrarySupport;
import com.oracle.svm.util.ReflectionUtil;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.Feature.IsInConfigurationAccess;
import org.graalvm.nativeimage.hosted.FieldValueTransformer;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.nativeimage.hosted.RuntimeResourceAccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Predicate;
import java.util.function.Supplier;

// Eventually, this feature should be moved to ACCP and enabled by a Native Image Build Configuration
// in ACCP's `META_INF/native-image/software.amazon.cryptools/AmazonCorrettoCryptoProvider/native-image.properties`.
// For more details see: https://www.graalvm.org/latest/reference-manual/native-image/overview/BuildConfiguration/
final class AccpFeature implements Feature {
    // From com.amazon.corretto.crypto.provider.Loader
    private static final String JNI_LIBRARY_NAME = "amazonCorrettoCryptoProvider";
    private static final String ACCP_PACKAGE = "com.amazon.corretto.crypto.provider.";
    private static final String ACCP_NAME = ACCP_PACKAGE + "AmazonCorrettoCryptoProvider";
    private static final String USE_EXTERNAL_LIB = ACCP_PACKAGE + "useExternalLibInNativeImage";

    @Override
    public String getDescription() {
        return "ACCP Support";
    }

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess a) {
        // Only activate if ACCP can be found in the class or module path.
        if (a.findClassByName(ACCP_NAME) != null) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess a) {
        String accpLibName = System.mapLibraryName(JNI_LIBRARY_NAME);
        Class<?> accpProvider = a.findClassByName(ACCP_NAME);
        assert accpProvider != null;
        // Add `version.properties` to the native image because it will be loaded at runtime.
        RuntimeResourceAccess.addResource(accpProvider.getModule(), "com/amazon/corretto/crypto/provider/version.properties");
        if (!Boolean.getBoolean(USE_EXTERNAL_LIB)) {
            // If we don't load `libamazonCorrettoCryptoProvider.so` from the file system, we have to embed it into the image.
            Path p = Path.of(System.getProperty("java.home"), "lib", accpLibName);
            // Now embed `libamazonCorrettoCryptoProvider.so` into the native image. It will be extracted and loaded at runtime.
            // Eventually, ACCP could provide a static version of `libamazonCorrettoCryptoProvider.so` which can be linked
            // right into the native image to avoid extraction and loading at runtime.
            // Another alternative is to use the shared library which `native-image` will by default place in the same directory
            // as the generated native image, but in that case, the native image wouldn't be self-contained anymore.
            if (Files.exists(p)) {
                // This is for the case where ACCP is bundled with the JDK and `libamazonCorrettoCryptoProvider.so`
                // can be found under the JDK's `lib/` directory.
                try {
                    byte[] accpBytes = Files.readAllBytes(p);
                    RuntimeResourceAccess.addResource(accpProvider.getModule(), "com/amazon/corretto/crypto/provider/" + accpLibName, accpBytes);
                } catch (IOException ioe) {
                    System.out.println("AccpFeature: WARNING : can't inject ACCP library " + p.toString());
                    ioe.printStackTrace(System.out);
                }
            } else {
                // This is for the case where ACCP is loaded from the module or classpath and `libamazonCorrettoCryptoProvider.so`
                // is bundled in ACCP's jar file.
                RuntimeResourceAccess.addResource(accpProvider.getModule(), "com/amazon/corretto/crypto/provider/" + accpLibName);
            }
        }
    }

    @Override
    public void duringSetup(DuringSetupAccess a) {
        RuntimeClassInitialization.initializeAtBuildTime("com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider");
        RuntimeClassInitialization.initializeAtBuildTime("com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider$ACCPService");
        RuntimeClassInitialization.initializeAtBuildTime("com.amazon.corretto.crypto.provider.ExtraCheck");
        RuntimeClassInitialization.initializeAtBuildTime("com.amazon.corretto.crypto.provider.Utils$NativeContextReleaseStrategy");

        RuntimeClassInitialization.initializeAtBuildTime("com.amazon.corretto.crypto.provider.SelfTestSuite");
        RuntimeClassInitialization.initializeAtBuildTime("com.amazon.corretto.crypto.provider.SelfTestSuite$SelfTest");
        RuntimeClassInitialization.initializeAtBuildTime("com.amazon.corretto.crypto.provider.SelfTestResult");
        RuntimeClassInitialization.initializeAtBuildTime("com.amazon.corretto.crypto.provider.SelfTestStatus");

        if (!Boolean.getBoolean(USE_EXTERNAL_LIB)) {
            // This is required to avoid copying `libamazonCorrettoCryptoProvider.so` near to the generated native executable as
            // a "required" library in `JNIRegistrationSupport::copyJDKLibraries()`. In the native image, ACCP will unpack and load
            // `libamazonCorrettoCryptoProvider.so` from the native image itself (see call to `RuntimeResourceAccess.addResource()`
            // in `beforeAnalysis()` above) in order to make it self-contained.
            NativeLibrarySupport.singleton().preregisterUninitializedBuiltinLibrary(JNI_LIBRARY_NAME);
        }
    }

    @Override
    public void afterImageWrite(AfterImageWriteAccess a) {
        if (Boolean.getBoolean(USE_EXTERNAL_LIB)) {
            String accpLibName = System.mapLibraryName(JNI_LIBRARY_NAME);
            Class<?> accpProvider = a.findClassByName(ACCP_NAME);
            assert accpProvider != null;

            Path destPath = a.getImagePath().resolveSibling(accpLibName);
            InputStream accpInputStream = accpProvider.getResourceAsStream("com/amazon/corretto/crypto/provider/" + accpLibName);
            if (accpInputStream != null) {
                try {
                    Files.copy(accpInputStream, destPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException | NullPointerException e) {
                    System.out.println("AccpFeature: WARNING : can't copy ACCP library to " + destPath);
                    e.printStackTrace(System.out);
                }
            } else {
                System.out.println("AccpFeature: WARNING : can't find com/amazon/corretto/crypto/provider/" + accpLibName);
            }
        }
    }

    @TargetClass(className = "com.amazon.corretto.crypto.provider.Loader", onlyWith = UseExternalLib.class)
    final static class Target_com_amazon_corretto_crypto_provider_Loader {
        @Substitute
        private static void tryLoadLibraryFromJar() throws IOException {
            throw new IOException("This native image was compiled with `" + USE_EXTERNAL_LIB + "=true` and therefore " +
                    "expects `libamazonCorrettoCryptoProvider.so` in the same directory like the native executable.");
        }
    }

    final static class UseExternalLib implements Predicate<String> {
        @Override
        public boolean test(String className) {
            return ReflectionUtil.lookupClass(true, className) != null && Boolean.getBoolean(USE_EXTERNAL_LIB);
        }
    }

}
