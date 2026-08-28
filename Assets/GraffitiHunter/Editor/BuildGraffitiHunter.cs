using System;
using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEngine;
using UnityEngine.Rendering;
using Unity.Burst;

namespace GraffitiHunter.Editor
{
    public static class BuildGraffitiHunter
    {
        [MenuItem("Graffiti Hunter/Build Demo APK")]
        public static void BuildDemoApk()
        {
            PrepareCleanGuiTheme();
            PlayerSettings.companyName = "Graffiti Hunter";
            PlayerSettings.productName = "Graffiti Hunter Demo";
            PlayerSettings.SetApplicationIdentifier(NamedBuildTarget.Android, "com.graffitihunter.demo");
            PlayerSettings.bundleVersion = "0.5.6";
            PlayerSettings.Android.bundleVersionCode = 33;
            var appIcon = AssetDatabase.LoadAssetAtPath<Texture2D>(
                "Assets/GraffitiHunter/Brand/GraffitiHunterIcon.png");
            if (appIcon != null)
                PlayerSettings.SetIcons(NamedBuildTarget.Android, new[] { appIcon }, IconKind.Application);
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel26;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevelAuto;
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.AutoRotation;
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            // A single broadly-supported API keeps this camera demo small and avoids
            // compiling the imported ML package's shader library twice.
            PlayerSettings.SetGraphicsAPIs(BuildTarget.Android, new[] { GraphicsDeviceType.OpenGLES3 });
            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Android, ScriptingImplementation.IL2CPP);
            BurstCompiler.Options.EnableBurstCompilation = false;

            string outputDirectory = Path.GetFullPath(Path.Combine(Application.dataPath, "..", "Builds"));
            Directory.CreateDirectory(outputDirectory);
            string apkPath = Path.Combine(outputDirectory, "GraffitiHunter-Demo.apk");
            string scene = "Assets/Scenes/SampleScene.unity";
            if (!File.Exists(Path.Combine(Path.GetDirectoryName(Application.dataPath) ?? "", scene)))
                throw new FileNotFoundException("The startup scene was not found.", scene);

            var options = new BuildPlayerOptions
            {
                scenes = new[] { scene },
                locationPathName = apkPath,
                target = BuildTarget.Android,
                options = BuildOptions.None
            };
            BuildReport report = BuildPipeline.BuildPlayer(options);
            if (report.summary.result != BuildResult.Succeeded)
                throw new Exception("Android build failed: " + report.summary.result);
            Debug.Log("Graffiti Hunter APK created at: " + apkPath);
        }

        private static void PrepareCleanGuiTheme()
        {
            const string resourcesFolder = "Assets/GraffitiHunter/Resources";
            const string themePath = resourcesFolder + "/GraffitiHunterTheme.asset";
            if (!AssetDatabase.IsValidFolder(resourcesFolder))
                AssetDatabase.CreateFolder("Assets/GraffitiHunter", "Resources");

            var theme = AssetDatabase.LoadAssetAtPath<GraffitiHunterTheme>(themePath);
            if (theme == null)
            {
                theme = ScriptableObject.CreateInstance<GraffitiHunterTheme>();
                AssetDatabase.CreateAsset(theme, themePath);
            }
            theme.panel = AssetDatabase.LoadAssetAtPath<Texture2D>(
                "Assets/honeti/gui_cartoon/Textures_v2/Panels/BasicPanel.png");
            theme.button = AssetDatabase.LoadAssetAtPath<Texture2D>(
                "Assets/honeti/gui_cartoon/Textures_v2/Buttons/ButtonRed.png");
            theme.buttonDown = AssetDatabase.LoadAssetAtPath<Texture2D>(
                "Assets/honeti/gui_cartoon/Textures_v2/Buttons/ButtonRedDown.png");
            EditorUtility.SetDirty(theme);
            AssetDatabase.SaveAssets();
        }
    }
}
