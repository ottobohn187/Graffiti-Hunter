using System.Collections.Generic;
using UnityEditor.Build;
using UnityEditor.Rendering;
using UnityEngine;
using UnityEngine.Rendering;

namespace GraffitiHunter.Editor
{
    // The imported offline ML demos are intentionally not used by Graffiti Hunter.
    // Strip their GPU kernels from this APK; Android's speech service handles the keyword.
    public sealed class DemoShaderStripper : IPreprocessShaders, IPreprocessComputeShaders
    {
        public int callbackOrder => 0;

        public void OnProcessShader(Shader shader, ShaderSnippetData snippet, IList<ShaderCompilerData> data)
        {
            if (shader.name.StartsWith("Hidden/Sentis") ||
                shader.name.Contains("Universal Render Pipeline") ||
                shader.name.Contains("Light2D"))
                data.Clear();
        }

        public void OnProcessComputeShader(ComputeShader shader, string kernelName, IList<ShaderCompilerData> data)
        {
            string path = UnityEditor.AssetDatabase.GetAssetPath(shader);
            if (path.Contains("com.unity.ai.inference")) data.Clear();
        }
    }
}
