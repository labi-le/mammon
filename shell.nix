# Dev shell for building the Android app. The SDK is unfree and
# license-gated, so nixpkgs is imported with the required config here
# (overridable by passing your own `pkgs`).
{ pkgs ? import <nixpkgs> {
    config.allowUnfree = true;
    config.android_sdk.accept_license = true;
  }
}:

let
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "35" ];
    # AGP refuses to run without exactly this build-tools revision.
    buildToolsVersions = [ "35.0.0" ];
  };
  androidSdk = androidComposition.androidsdk;
  sdkRoot = "${androidSdk}/libexec/android-sdk";
in
pkgs.mkShell {
  packages = with pkgs; [
    jdk17
    git
    androidSdk
  ];

  ANDROID_HOME = sdkRoot;
  ANDROID_SDK_ROOT = sdkRoot;
  JAVA_HOME = "${pkgs.jdk17}";

  shellHook = ''
    echo "mammon shell: ANDROID_HOME=$ANDROID_HOME, JAVA_HOME=$JAVA_HOME"
    echo "build the app: ./gradlew :app:assembleDebug"
  '';
}
