using System.IO;
using UnityEditor.Android;

namespace GraffitiHunter.Editor
{
    public sealed class AndroidGradleDependencies : IPostGenerateGradleAndroidProject
    {
        public int callbackOrder => 100;

        public void OnPostGenerateGradleAndroidProject(string path)
        {
            string gradlePath = Path.Combine(path, "build.gradle");
            string gradle = File.ReadAllText(gradlePath);
            const string marker = "implementation fileTree(dir: 'libs', include: ['*.jar'])";
            if (gradle.Contains("androidx.media3:media3-exoplayer:")) return;
            gradle = gradle.Replace(marker, marker +
                "\n    implementation 'androidx.media3:media3-exoplayer:1.10.1'" +
                "\n    implementation 'androidx.media3:media3-exoplayer-rtsp:1.10.1'");
            File.WriteAllText(gradlePath, gradle);
        }
    }
}
