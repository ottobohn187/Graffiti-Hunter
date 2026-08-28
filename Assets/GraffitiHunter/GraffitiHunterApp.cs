using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using UnityEngine;
using UnityEngine.Android;
using UnityEngine.Networking;

namespace GraffitiHunter
{
    public sealed class GraffitiHunterApp : MonoBehaviour
    {
        [Serializable]
        private sealed class Report
        {
            public string id;
            public string capturedUtc;
            public double cameraLatitude;
            public double cameraLongitude;
            public double horizontalAccuracyMeters;
            public double bearingDegrees;
            public double estimatedDistanceMeters;
            public double estimatedTargetLatitude;
            public double estimatedTargetLongitude;
            public string photoFile;
            public string mapUrl;
            public string note;
            public string approvalStatus;
            public string approvedUtc;
            public string getItDoneCategory;
            public string suggestedDescription;
            public string getItDoneFormUrl;
            public string locationType;
            public string offensive;
            public string exactLocationDescription;
            public string streetAddress;
            public float digitalZoom;
        }

        [Serializable]
        private sealed class ReverseGeocodeResult { public string display_name; }

        private WebCamTexture cameraTexture;
        private RenderTexture uprightCamera;
        private Material cameraRotateMaterial;
        private Texture2D capturedPreview;
        private Texture2D mapPreview;
        private GUIStyle titleStyle;
        private GUIStyle statusStyle;
        private GUIStyle buttonStyle;
        private GUIStyle labelStyle;
        private GraffitiHunterTheme theme;
        private string status = "Starting camera and sensors…";
        private string lastReportPath = "";
        private float bearing;
        private float magneticBearing;
        private float magneticDeclination;
        private float nextDeclinationUpdate;
        private float mapMarkerX = 0.5f;
        private float mapMarkerY = 0.5f;
        private double latitude;
        private double longitude;
        private double accuracy;
        private bool locationReady;
        private bool isCapturing;
        private bool voiceEnabled;
        private bool reviewing;
        private Report pendingReport;
        private string pendingFolder;
        private bool cameraPermissionGranted;
        private bool locationPermissionGranted;
        private bool microphonePermissionGranted;
        private AndroidJavaObject speechRecognizer;
        private AndroidJavaObject speechIntent;
        private AndroidJavaProxy speechListener;
        private float restartVoiceAt;
        private AndroidJavaObject sensorManager;
        private AndroidJavaObject rotationSensor;
        private AndroidJavaProxy sensorListener;
        private int queuedReportCount;
        private bool queueDeleteConfirm;
        private bool queueMode;
        private readonly List<Report> queuedReports = new List<Report>();
        private readonly List<string> queuedFolders = new List<string>();
        private int queueIndex;
        private Texture2D queuePhoto;
        private Texture2D queueMap;
        private int queueCategoryIndex;
        private bool queueMediaFullscreen;
        private Texture2D fullscreenQueueTexture;
        private float digitalZoom = 1f;
        private bool nativeCameraPending;
        private bool launchActionHandled;
        private string transitionMessage = "Opening...";
        private bool nativeCaptureLandscape;
        private static readonly string[] LocationTypes =
        {
            "Private Residence", "Commercial Property", "USPS Mail Box", "Bus Shelter",
            "Street or Sidewalk", "City Sign", "Utility Box", "Street Lights",
            "Park", "Utility Pole", "Dumpster", "Other"
        };
        private const double DemoTargetDistanceMeters = 15.0;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        private static void StartApp()
        {
            if (FindAnyObjectByType<GraffitiHunterApp>() != null) return;
            var host = new GameObject("Graffiti Hunter");
            DontDestroyOnLoad(host);
            host.AddComponent<GraffitiHunterApp>();
        }

        private IEnumerator Start()
        {
            theme = Resources.Load<GraffitiHunterTheme>("GraffitiHunterTheme");
            queuedReportCount = CountQueuedReports();
            Screen.sleepTimeout = SleepTimeout.NeverSleep;
            Screen.orientation = ScreenOrientation.AutoRotation;
            Screen.autorotateToPortrait = true;
            Screen.autorotateToPortraitUpsideDown = false;
            Screen.autorotateToLandscapeLeft = true;
            Screen.autorotateToLandscapeRight = true;
            string launchAction = ConsumeLaunchAction();
            launchActionHandled = true;
            if (launchAction == "queue")
            {
                SubmitQueuedReports();
                yield break;
            }
            if (launchAction == "review")
            {
                nativeCameraPending = true;
                transitionMessage = "Loading photo and map...";
                yield return StartCoroutine(ImportNativeCameraCapture());
                yield break;
            }
            if (launchAction != "capture")
            {
                ReturnToNativeHome();
                yield break;
            }

            transitionMessage = "Opening camera...";
            yield return RequestPermission(Permission.Camera, "camera", granted => cameraPermissionGranted = granted);
            if (cameraPermissionGranted) StartCamera();
            else status = "Camera permission denied. Enable Camera in Android Settings.";

            yield return RequestPermission(Permission.FineLocation, "location", granted => locationPermissionGranted = granted);
            if (locationPermissionGranted)
            {
                Input.location.Start(1f, 0.5f);
                yield return StartCoroutine(WaitForLocation());
            }

            BeginCapture();
        }

        private string ConsumeLaunchAction()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
            using var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
            using var prefs = activity.Call<AndroidJavaObject>("getSharedPreferences", "GraffitiHunterNavigation", 0);
            string action = prefs.Call<string>("getString", "launch_action", "");
            prefs.Call<AndroidJavaObject>("edit").Call<AndroidJavaObject>("remove", "launch_action").Call("apply");
            return action ?? "";
#else
            return "capture";
#endif
        }

        private IEnumerator RequestPermission(string permission, string label, Action<bool> completed)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (Permission.HasUserAuthorizedPermission(permission))
            {
                completed(true);
                yield break;
            }

            bool answered = false;
            bool granted = false;
            var callbacks = new PermissionCallbacks();
            callbacks.PermissionGranted += _ => { granted = true; answered = true; };
            callbacks.PermissionDenied += _ => { answered = true; };
            callbacks.PermissionDeniedAndDontAskAgain += _ => { answered = true; };
            status = "Please allow " + label + " access.";
            Permission.RequestUserPermission(permission, callbacks);
            float timeout = Time.realtimeSinceStartup + 45f;
            while (!answered && Time.realtimeSinceStartup < timeout)
                yield return null;
            completed(granted || Permission.HasUserAuthorizedPermission(permission));
#else
            completed(true);
            yield return null;
#endif
        }

        private void StartCamera()
        {
            if (WebCamTexture.devices.Length == 0)
            {
                status = "No camera was found.";
                return;
            }

            WebCamDevice selected = WebCamTexture.devices[0];
            foreach (var device in WebCamTexture.devices)
                if (!device.isFrontFacing) { selected = device; break; }

            cameraTexture = new WebCamTexture(selected.name, 1920, 1080, 30);
            cameraTexture.Play();
            Shader rotationShader = Shader.Find("Hidden/GraffitiHunter/RotateCamera");
            if (rotationShader != null) cameraRotateMaterial = new Material(rotationShader);
            status = "Camera ready. Tap CAPTURE.";
        }

        private void SetZoom(float zoom)
        {
            digitalZoom = zoom;
            StartCoroutine(FocusDistantSubject());
        }

        private IEnumerator FocusDistantSubject()
        {
            if (cameraTexture == null) yield break;
            status = "Focusing on distant center target...";
            cameraTexture.Stop();
            yield return new WaitForSecondsRealtime(0.18f);
            cameraTexture.Play();
            // Samsung's camera service resumes in continuous-picture AF mode.
            // Give it time to settle on the subject inside the center guide.
            yield return new WaitForSecondsRealtime(1.1f);
            status = "Focus refreshed. Hold steady, then CAPTURE.";
        }

        private IEnumerator WaitForLocation()
        {
            float timeout = Time.realtimeSinceStartup + 20f;
            while (Input.location.status == LocationServiceStatus.Initializing && Time.realtimeSinceStartup < timeout)
                yield return null;
            UpdateSensors();
            if (!locationReady)
                status = "Camera ready. Waiting for GPS — you can still take a demo photo.";
        }

        private void Update()
        {
            UpdateSensors();
            UpdateCameraRender();
            if (voiceEnabled && restartVoiceAt > 0 && Time.realtimeSinceStartup >= restartVoiceAt)
            {
                restartVoiceAt = 0;
                BeginListening();
            }
        }

        private void UpdateCameraRender()
        {
            if (cameraTexture == null || !cameraTexture.isPlaying || cameraTexture.width < 100 || cameraRotateMaterial == null) return;
            int rotation = ((cameraTexture.videoRotationAngle % 360) + 360) % 360;
            int outputWidth = rotation == 90 || rotation == 270 ? cameraTexture.height : cameraTexture.width;
            int outputHeight = rotation == 90 || rotation == 270 ? cameraTexture.width : cameraTexture.height;
            if (uprightCamera == null || uprightCamera.width != outputWidth || uprightCamera.height != outputHeight)
            {
                if (uprightCamera != null) uprightCamera.Release();
                uprightCamera = new RenderTexture(outputWidth, outputHeight, 0, RenderTextureFormat.ARGB32);
                uprightCamera.Create();
            }
            cameraRotateMaterial.SetFloat("_QuarterTurns", rotation / 90);
            cameraRotateMaterial.SetFloat("_MirrorY", cameraTexture.videoVerticallyMirrored ? 1f : 0f);
            Graphics.Blit(cameraTexture, uprightCamera, cameraRotateMaterial);
        }

        private void UpdateSensors()
        {
            if (Input.location.status == LocationServiceStatus.Running)
            {
                var data = Input.location.lastData;
                latitude = data.latitude;
                longitude = data.longitude;
                accuracy = data.horizontalAccuracy;
                locationReady = true;
                UpdateMagneticDeclination();
            }
        }

        private void UpdateMagneticDeclination()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (Time.realtimeSinceStartup < nextDeclinationUpdate) return;
            nextDeclinationUpdate = Time.realtimeSinceStartup + 30f;
            try
            {
                using var field = new AndroidJavaObject("android.hardware.GeomagneticField",
                    (float)latitude, (float)longitude, 0f, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                magneticDeclination = field.Call<float>("getDeclination");
                bearing = (magneticBearing + magneticDeclination + 360f) % 360f;
            }
            catch (Exception ex) { Debug.LogWarning("Declination unavailable: " + ex.Message); }
#endif
        }

        public void VoiceCommand(string phrase)
        {
            if (!string.IsNullOrWhiteSpace(phrase) &&
                phrase.IndexOf("graffiti", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                status = "Voice command heard — capturing…";
                BeginCapture();
            }
        }

        private void StartHeadingSensor()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                using var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
                var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
                sensorManager = activity.Call<AndroidJavaObject>("getSystemService", "sensor");
                rotationSensor = sensorManager.Call<AndroidJavaObject>("getDefaultSensor", 11);
                if (rotationSensor != null)
                {
                    sensorListener = new RotationVectorListener(this);
                    sensorManager.Call<bool>("registerListener", sensorListener, rotationSensor, 2);
                }
            }
            catch (Exception ex) { Debug.LogWarning("Heading sensor unavailable: " + ex.Message); }
#endif
        }

        internal void UpdateNativeHeading(float[] vector)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                if (vector == null || vector.Length < 3) return;
                float x = vector[0];
                float y = vector[1];
                float z = vector[2];
                float w = vector.Length >= 4
                    ? vector[3]
                    : Mathf.Sqrt(Mathf.Max(0f, 1f - x * x - y * y - z * z));
                float r01 = 2f * (x * y - z * w);
                float r11 = 1f - 2f * (x * x + z * z);
                float degrees = Mathf.Atan2(r01, r11) * Mathf.Rad2Deg;
                magneticBearing = (degrees + 360f) % 360f;
                bearing = (magneticBearing + magneticDeclination + 360f) % 360f;
            }
            catch (Exception ex) { Debug.LogWarning("Heading update failed: " + ex.Message); }
#endif
        }

        private void StartVoiceRecognition()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                using var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
                var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
                using var recognizerClass = new AndroidJavaClass("android.speech.SpeechRecognizer");
                bool available = recognizerClass.CallStatic<bool>("isRecognitionAvailable", activity);
                if (!available) { status = "Voice recognition unavailable; use CAPTURE."; return; }

                speechRecognizer = recognizerClass.CallStatic<AndroidJavaObject>("createSpeechRecognizer", activity);
                speechListener = new SpeechListener(this);
                speechRecognizer.Call("setRecognitionListener", speechListener);
                speechIntent = new AndroidJavaObject("android.content.Intent", "android.speech.action.RECOGNIZE_SPEECH");
                speechIntent.Call<AndroidJavaObject>("putExtra", "android.speech.extra.LANGUAGE_MODEL", "free_form");
                speechIntent.Call<AndroidJavaObject>("putExtra", "android.speech.extra.PARTIAL_RESULTS", true);
                speechIntent.Call<AndroidJavaObject>("putExtra", "android.speech.extra.MAX_RESULTS", 5);
                voiceEnabled = true;
                BeginListening();
            }
            catch (Exception ex)
            {
                Debug.LogWarning("Voice recognition could not start: " + ex.Message);
                status = "Camera ready. Voice unavailable; use CAPTURE.";
            }
#endif
        }

        private void BeginListening()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (!voiceEnabled || speechRecognizer == null) return;
            try { speechRecognizer.Call("startListening", speechIntent); }
            catch { restartVoiceAt = Time.realtimeSinceStartup + 1.5f; }
#endif
        }

        internal void ProcessSpeechResults(AndroidJavaObject bundle)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                using var recognizerClass = new AndroidJavaClass("android.speech.SpeechRecognizer");
                string key = recognizerClass.GetStatic<string>("RESULTS_RECOGNITION");
                using var results = bundle.Call<AndroidJavaObject>("getStringArrayList", key);
                if (results != null)
                {
                    int count = results.Call<int>("size");
                    for (int i = 0; i < count; i++) VoiceCommand(results.Call<string>("get", i));
                }
            }
            catch (Exception ex) { Debug.LogWarning(ex.Message); }
            restartVoiceAt = Time.realtimeSinceStartup + 0.8f;
#endif
        }

        internal void VoiceSessionEnded()
        {
            restartVoiceAt = Time.realtimeSinceStartup + 0.8f;
        }

        private IEnumerator CaptureReport()
        {
            if (isCapturing || cameraTexture == null || !cameraTexture.isPlaying || cameraTexture.width < 100) yield break;
            isCapturing = true;
            yield return new WaitForEndOfFrame();

            // Android protects the GPU camera surface from screen/readback capture.
            // The WebCamTexture CPU pixels remain available for evidence photos.
            int width = cameraTexture.width;
            int height = cameraTexture.height;
            var photo = new Texture2D(width, height, TextureFormat.RGB24, false);
            photo.SetPixels32(cameraTexture.GetPixels32());
            photo.Apply();
            photo = RotateTextureClockwise(photo, cameraTexture.videoRotationAngle);
            bool capturingLandscape = Screen.width > Screen.height;
            if (capturingLandscape && photo.height > photo.width)
                photo = RotateTextureClockwise(photo, 90);
            else if (!capturingLandscape && photo.width > photo.height)
                photo = RotateTextureClockwise(photo, 90);
            if (digitalZoom > 1.01f) photo = ApplyDigitalZoom(photo, digitalZoom);
            FinishCapturedPhoto(photo);
            yield break;
        }

        private void BeginCapture()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (isCapturing || nativeCameraPending) return;
            nativeCameraPending = true;
            nativeCaptureLandscape = Screen.width > Screen.height;
            status = "Opening real-lens camera...";
            if (cameraTexture != null && cameraTexture.isPlaying)
                cameraTexture.Stop();
            using var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
            using var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
            using var intent = new AndroidJavaObject("android.content.Intent");
            intent.Call<AndroidJavaObject>("setClassName", "com.graffitihunter.demo",
                "com.graffitihunter.demo.NativeCameraCaptureActivity");
            activity.Call("startActivity", intent);
#else
            StartCoroutine(CaptureReport());
#endif
        }

        private void ReturnToNativeHome()
        {
            queueMode = false;
            queueMediaFullscreen = false;
            Screen.orientation = ScreenOrientation.AutoRotation;
            if (queuePhoto != null) Destroy(queuePhoto);
            if (queueMap != null) Destroy(queueMap);
            queuePhoto = null;
            queueMap = null;
#if UNITY_ANDROID && !UNITY_EDITOR
            using var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
            using var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
            using var intent = new AndroidJavaObject("android.content.Intent");
            intent.Call<AndroidJavaObject>("setClassName", "com.graffitihunter.demo",
                "com.graffitihunter.demo.HunterHomeActivity");
            intent.Call<AndroidJavaObject>("addFlags", 0x04000000); // FLAG_ACTIVITY_CLEAR_TOP
            activity.Call("startActivity", intent);
            activity.Call("finish");
#endif
        }

        private void OnApplicationFocus(bool hasFocus)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (hasFocus)
            {
                if (cameraTexture != null && !cameraTexture.isPlaying)
                    cameraTexture.Play();
                if (nativeCameraPending)
                    StartCoroutine(ImportNativeCameraCapture());
                else if (launchActionHandled)
                {
                    string action = ConsumeLaunchAction();
                    if (action == "capture") BeginCapture();
                    else if (action == "queue") SubmitQueuedReports();
                }
            }
#endif
        }

        private IEnumerator ImportNativeCameraCapture()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            yield return new WaitForSecondsRealtime(0.35f);
            string path = "";
            int captureRotation = 0;
            long captureLatBits = 0;
            long captureLonBits = 0;
            long captureLocationTime = 0;
            float captureAccuracy = 0;
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            using (var prefs = activity.Call<AndroidJavaObject>("getSharedPreferences",
                       "GraffitiHunterNativeCamera", 0))
            {
                path = prefs.Call<string>("getString", "capture_path", "");
                captureRotation = prefs.Call<int>("getInt", "capture_rotation", 0);
                captureLatBits = prefs.Call<long>("getLong", "capture_lat_bits", 0L);
                captureLonBits = prefs.Call<long>("getLong", "capture_lon_bits", 0L);
                captureAccuracy = prefs.Call<float>("getFloat", "capture_accuracy", 0f);
                captureLocationTime = prefs.Call<long>("getLong", "capture_location_time", 0L);
                if (!string.IsNullOrEmpty(path))
                    prefs.Call<AndroidJavaObject>("edit")
                        .Call<AndroidJavaObject>("remove", "capture_path")
                        .Call<AndroidJavaObject>("remove", "capture_rotation")
                        .Call<AndroidJavaObject>("remove", "capture_lat_bits")
                        .Call<AndroidJavaObject>("remove", "capture_lon_bits")
                        .Call<AndroidJavaObject>("remove", "capture_accuracy")
                        .Call<AndroidJavaObject>("remove", "capture_location_time")
                        .Call("apply");
            }
            nativeCameraPending = false;
            if (string.IsNullOrEmpty(path) || !File.Exists(path))
            {
                status = "Camera cancelled. Returning to main menu.";
                ReturnToNativeHome();
                yield break;
            }
            byte[] bytes = File.ReadAllBytes(path);
            try { File.Delete(path); } catch { }
            var photo = new Texture2D(2, 2, TextureFormat.RGB24, false);
            if (!ImageConversion.LoadImage(photo, bytes))
            {
                Destroy(photo);
                status = "Samsung camera image could not be loaded.";
                yield break;
            }
            captureRotation = ((captureRotation % 360) + 360) % 360;
            // Samsung stores Camera2 rotation as JPEG metadata. Unity's loader
            // ignores it, so bake the device rotation into the saved pixels.
            if ((captureRotation == 90 || captureRotation == 270) && photo.width > photo.height)
                photo = RotateTextureClockwise(photo, captureRotation);
            else if (captureRotation == 180)
                photo = RotateTextureClockwise(photo, 180);
            if (captureLocationTime > 0)
            {
                latitude = BitConverter.Int64BitsToDouble(captureLatBits);
                longitude = BitConverter.Int64BitsToDouble(captureLonBits);
                accuracy = captureAccuracy;
                locationReady = true;
            }
            FinishCapturedPhoto(photo);
#else
            yield break;
#endif
        }

        private void FinishCapturedPhoto(Texture2D photo)
        {
            isCapturing = true;
            int width = photo.width;
            int height = photo.height;

            // Bake the red demo targeting box into the saved evidence photo.
            int boxWidth = Mathf.RoundToInt(width * 0.62f);
            int boxHeight = Mathf.RoundToInt(height * 0.42f);
            int left = (width - boxWidth) / 2;
            int bottom = (height - boxHeight) / 2;
            DrawBox(photo, left, bottom, boxWidth, boxHeight, Mathf.Max(5, width / 180), Color.red);
            DrawTimestamp(photo, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture));

            string id = DateTime.UtcNow.ToString("yyyyMMdd_HHmmss", CultureInfo.InvariantCulture);
            string folder = Path.Combine(Application.persistentDataPath, "GraffitiReports", id);
            Directory.CreateDirectory(folder);
            string photoName = "graffiti_" + id + ".jpg";
            byte[] photoBytes = photo.EncodeToJPG(92);
            File.WriteAllBytes(Path.Combine(folder, photoName), photoBytes);
            bool galleryPhotoSaved = SaveToDcim(photoName, "image/jpeg", photoBytes);

            double targetLat = latitude;
            double targetLon = longitude;
            string mapUrl = string.Format(CultureInfo.InvariantCulture,
                "https://www.openstreetmap.org/?mlat={0:F7}&mlon={1:F7}#map=19/{0:F7}/{1:F7}", targetLat, targetLon);
            var report = new Report
            {
                id = id,
                capturedUtc = DateTime.UtcNow.ToString("o", CultureInfo.InvariantCulture),
                cameraLatitude = latitude,
                cameraLongitude = longitude,
                horizontalAccuracyMeters = accuracy,
                bearingDegrees = 0,
                estimatedDistanceMeters = 0,
                estimatedTargetLatitude = targetLat,
                estimatedTargetLongitude = targetLon,
                photoFile = photoName,
                mapUrl = mapUrl,
                approvalStatus = "awaiting_approval",
                getItDoneCategory = "Graffiti",
                locationType = "Other",
                offensive = "No",
                digitalZoom = digitalZoom,
                getItDoneFormUrl = "https://getitdone.sandiego.gov/TSWNewReport?type=Graffiti",
                note = locationReady
                    ? "Map location is the phone's GPS position at capture time; compass direction is not used."
                    : "GPS was unavailable; coordinates are placeholders and must not be submitted."
            };
            RefreshSubmissionDescription(report);
            string reportJson = JsonUtility.ToJson(report, true);
            File.WriteAllText(Path.Combine(folder, "report.json"), reportJson);
            File.WriteAllText(Path.Combine(folder, "map.url"), "[InternetShortcut]\r\nURL=" + mapUrl + "\r\n");
            SaveToDcim("report_" + id + ".json", "application/json",
                System.Text.Encoding.UTF8.GetBytes(reportJson));

            if (capturedPreview != null) Destroy(capturedPreview);
            capturedPreview = photo;
            lastReportPath = folder;
            pendingReport = report;
            pendingFolder = folder;
            reviewing = true;
            if (locationReady) StartCoroutine(LoadMapPreview(latitude, longitude));
            if (locationReady) StartCoroutine(ReverseGeocodeReport(report, folder));
            status = locationReady ? "Report saved ✓" : "Demo photo saved — GPS was not ready";
            status = galleryPhotoSaved
                ? "Photo saved to Gallery - review"
                : "Photo captured - Gallery save failed";
            isCapturing = false;
        }

        private void ApproveReport()
        {
            if (!reviewing || string.IsNullOrEmpty(pendingFolder) || pendingReport == null) return;
            pendingReport.approvalStatus = "pending_get_it_done";
            pendingReport.approvedUtc = DateTime.UtcNow.ToString("o", CultureInfo.InvariantCulture);
            string submissionJson = JsonUtility.ToJson(pendingReport, true);
            File.WriteAllText(Path.Combine(pendingFolder, "report.json"), submissionJson);
            File.WriteAllText(Path.Combine(pendingFolder, "pending_get_it_done.json"), submissionJson);
            File.WriteAllText(Path.Combine(pendingFolder, "APPROVED.txt"),
                "Approved and queued " + pendingReport.approvedUtc);
            SaveToDcim("submission_" + pendingReport.id + ".json", "application/json",
                System.Text.Encoding.UTF8.GetBytes(submissionJson));
            queuedReportCount = CountQueuedReports();
            reviewing = false;
            status = "Approved & queued - reopening camera";
            StartCoroutine(ReturnToRapidCapture());
        }

        private IEnumerator ReturnToRapidCapture()
        {
            yield return new WaitForSecondsRealtime(0.2f);
            BeginCapture();
        }

        private static int CountQueuedReports()
        {
            string root = Path.Combine(Application.persistentDataPath, "GraffitiReports");
            if (!Directory.Exists(root)) return 0;
            int count = 0;
            foreach (string folder in Directory.GetDirectories(root))
                if (File.Exists(Path.Combine(folder, "pending_get_it_done.json")))
                    count++;
            return count;
        }

        private void SubmitQueuedReports()
        {
            LoadSubmissionQueue();
            queueMode = true;
            queueIndex = 0;
            Screen.orientation = ScreenOrientation.Portrait;
            if (queuedReports.Count < 1)
            {
                status = "No approved reports are queued";
                return;
            }
            LoadQueuePreview();
        }

        private void LoadSubmissionQueue()
        {
            queuedReports.Clear();
            queuedFolders.Clear();
            string root = Path.Combine(Application.persistentDataPath, "GraffitiReports");
            if (!Directory.Exists(root)) return;
            foreach (string folder in Directory.GetDirectories(root))
            {
                string path = Path.Combine(folder, "pending_get_it_done.json");
                if (!File.Exists(path)) continue;
                try
                {
                    Report report = JsonUtility.FromJson<Report>(File.ReadAllText(path));
                    if (report == null) continue;
                    if (string.IsNullOrEmpty(report.locationType)) report.locationType = "Other";
                    if (string.IsNullOrEmpty(report.offensive)) report.offensive = "No";
                    RefreshSubmissionDescription(report);
                    queuedReports.Add(report);
                    queuedFolders.Add(folder);
                }
                catch (Exception exception) { Debug.LogWarning("Could not load queued report: " + exception.Message); }
            }
            queuedReportCount = queuedReports.Count;
        }

        private void LoadQueuePreview()
        {
            queueDeleteConfirm = false;
            if (queuePhoto != null) Destroy(queuePhoto);
            if (queueMap != null) Destroy(queueMap);
            queuePhoto = null;
            queueMap = null;
            if (queueIndex < 0 || queueIndex >= queuedReports.Count) return;
            Report report = queuedReports[queueIndex];
            string folder = queuedFolders[queueIndex];
            string photoPath = Path.Combine(folder, report.photoFile ?? "");
            string mapPath = Path.Combine(folder, "map.png");
            if (File.Exists(photoPath))
            {
                queuePhoto = new Texture2D(2, 2, TextureFormat.RGB24, false);
                ImageConversion.LoadImage(queuePhoto, File.ReadAllBytes(photoPath));
            }
            if (File.Exists(mapPath))
            {
                queueMap = new Texture2D(2, 2, TextureFormat.RGB24, false);
                ImageConversion.LoadImage(queueMap, File.ReadAllBytes(mapPath));
            }
            queueCategoryIndex = Mathf.Max(0, Array.IndexOf(LocationTypes, report.locationType));
            if (string.IsNullOrWhiteSpace(report.streetAddress))
                StartCoroutine(ReverseGeocodeReport(report, folder));
        }

        private void UpdateQueuePackage()
        {
            if (queueIndex < 0 || queueIndex >= queuedReports.Count) return;
            Report report = queuedReports[queueIndex];
            report.locationType = LocationTypes[queueCategoryIndex];
            RefreshSubmissionDescription(report);
            string json = JsonUtility.ToJson(report, true);
            File.WriteAllText(Path.Combine(queuedFolders[queueIndex], "report.json"), json);
            File.WriteAllText(Path.Combine(queuedFolders[queueIndex], "pending_get_it_done.json"), json);
            status = "Submission package updated";
        }

        private static void RefreshSubmissionDescription(Report report)
        {
            string place = string.IsNullOrWhiteSpace(report.streetAddress)
                ? "Possible address not yet available"
                : "Possible address: " + report.streetAddress;
            report.suggestedDescription = string.Format(CultureInfo.InvariantCulture,
                "Graffiti on/at a {0}. {1}. GPS coordinates at capture: {2:F7}, {3:F7}. " +
                "Photo and marked location map are attached. Captured and submitted with " +
                "Graffiti Hunter Build 0.5.6 Beta (home made app).",
                report.locationType, place, report.estimatedTargetLatitude, report.estimatedTargetLongitude);
            report.exactLocationDescription = string.Format(CultureInfo.InvariantCulture,
                "{0}. Map pin: {1:F7}, {2:F7}. Look for the location marked by the red arrow.",
                place, report.estimatedTargetLatitude, report.estimatedTargetLongitude);
        }

        private IEnumerator ReverseGeocodeReport(Report report, string folder)
        {
            string url = string.Format(CultureInfo.InvariantCulture,
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat={0:F7}&lon={1:F7}&zoom=18",
                report.estimatedTargetLatitude, report.estimatedTargetLongitude);
            using var request = UnityWebRequest.Get(url);
            request.SetRequestHeader("User-Agent", "GraffitiHunterDemo/0.5.6");
            request.timeout = 12;
            yield return request.SendWebRequest();
            if (request.result != UnityWebRequest.Result.Success) yield break;
            ReverseGeocodeResult result = JsonUtility.FromJson<ReverseGeocodeResult>(request.downloadHandler.text);
            if (result == null || string.IsNullOrWhiteSpace(result.display_name)) yield break;
            report.streetAddress = result.display_name;
            RefreshSubmissionDescription(report);
            string json = JsonUtility.ToJson(report, true);
            File.WriteAllText(Path.Combine(folder, "report.json"), json);
            string pendingPath = Path.Combine(folder, "pending_get_it_done.json");
            if (File.Exists(pendingPath)) File.WriteAllText(pendingPath, json);
        }

        private void MarkQueuePackageProcessed()
        {
            if (queueIndex < 0 || queueIndex >= queuedReports.Count) return;
            UpdateQueuePackage();
            string folder = queuedFolders[queueIndex];
            string pendingPath = Path.Combine(folder, "pending_get_it_done.json");
            if (File.Exists(pendingPath)) File.Delete(pendingPath);
            File.WriteAllText(Path.Combine(folder, "PROCESSED.txt"),
                "Marked processed " + DateTime.UtcNow.ToString("o", CultureInfo.InvariantCulture));
            RemoveCurrentQueueItem("Package marked processed");
        }

        private void DeleteQueuePackage()
        {
            if (queueIndex < 0 || queueIndex >= queuedReports.Count) return;
            string folder = queuedFolders[queueIndex];
            if (Directory.Exists(folder)) Directory.Delete(folder, true);
            RemoveCurrentQueueItem("Package deleted");
        }

        private void RemoveCurrentQueueItem(string message)
        {
            queuedReports.RemoveAt(queueIndex);
            queuedFolders.RemoveAt(queueIndex);
            queuedReportCount = queuedReports.Count;
            queueDeleteConfirm = false;
            if (queuedReports.Count == 0)
            {
                queueIndex = 0;
                if (queuePhoto != null) Destroy(queuePhoto);
                if (queueMap != null) Destroy(queueMap);
                queuePhoto = null;
                queueMap = null;
                ReturnToNativeHome();
            }
            else
            {
                queueIndex = Mathf.Clamp(queueIndex, 0, queuedReports.Count - 1);
                LoadQueuePreview();
            }
            status = message;
        }

        private void PrepareQueuedWebsite()
        {
            StartCoroutine(PrepareQueuedWebsiteRoutine());
        }

        private IEnumerator PrepareQueuedWebsiteRoutine()
        {
            if (queueIndex < 0 || queueIndex >= queuedReports.Count) yield break;
            UpdateQueuePackage();
            Report report = queuedReports[queueIndex];
            string folder = queuedFolders[queueIndex];
            if (string.IsNullOrWhiteSpace(report.streetAddress))
            {
                status = "Finding street address...";
                yield return StartCoroutine(ReverseGeocodeReport(report, folder));
            }
#if UNITY_ANDROID && !UNITY_EDITOR
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            using (var intent = new AndroidJavaObject("android.content.Intent"))
            {
                intent.Call<AndroidJavaObject>("setClassName", "com.graffitihunter.demo",
                    "com.graffitihunter.demo.GraffitiSubmissionActivity");
                intent.Call<AndroidJavaObject>("putExtra", "latitude",
                    report.estimatedTargetLatitude.ToString("F7", CultureInfo.InvariantCulture));
                intent.Call<AndroidJavaObject>("putExtra", "longitude",
                    report.estimatedTargetLongitude.ToString("F7", CultureInfo.InvariantCulture));
                intent.Call<AndroidJavaObject>("putExtra", "streetAddress", report.streetAddress ?? "");
                intent.Call<AndroidJavaObject>("putExtra", "locationType", report.locationType);
                intent.Call<AndroidJavaObject>("putExtra", "offensive", report.offensive);
                intent.Call<AndroidJavaObject>("putExtra", "description", report.suggestedDescription);
                intent.Call<AndroidJavaObject>("putExtra", "locationDescription", report.exactLocationDescription);
                intent.Call<AndroidJavaObject>("putExtra", "photoPath", Path.Combine(folder, report.photoFile));
                intent.Call<AndroidJavaObject>("putExtra", "mapPath", Path.Combine(folder, "map.png"));
                activity.Call("startActivity", intent);
            }
#endif
        }

        private void OpenCityAccount()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            using (var intent = new AndroidJavaObject("android.content.Intent"))
            {
                intent.Call<AndroidJavaObject>("setClassName", "com.graffitihunter.demo",
                    "com.graffitihunter.demo.GraffitiSubmissionActivity");
                intent.Call<AndroidJavaObject>("putExtra", "accountMode", true);
                intent.Call<AndroidJavaObject>("putExtra", "startUrl",
                    "https://getitdone.sandiego.gov/TSWViewReportByList#login-modal");
                activity.Call("startActivity", intent);
            }
#endif
        }

        private void ResetCapture()
        {
            if (capturedPreview != null) Destroy(capturedPreview);
            if (mapPreview != null) Destroy(mapPreview);
            capturedPreview = null;
            mapPreview = null;
            pendingReport = null;
            pendingFolder = null;
            reviewing = false;
            status = "Retaking photo...";
            BeginCapture();
        }

        private IEnumerator LoadMapPreview(double lat, double lon)
        {
            const int zoom = 16;
            const int tileSize = 256;
            int n = 1 << zoom;
            double globalTileX = (lon + 180.0) / 360.0 * n;
            int tileX = Mathf.Clamp((int)Math.Floor(globalTileX), 0, n - 1);
            double latRad = lat * Math.PI / 180.0;
            double globalTileY = (1.0 - Math.Log(Math.Tan(latRad) + 1.0 / Math.Cos(latRad)) / Math.PI) / 2.0 * n;
            int tileY = Mathf.Clamp((int)Math.Floor(globalTileY), 0, n - 1);

            var mosaic = new Texture2D(tileSize * 3, tileSize * 3, TextureFormat.RGB24, false);
            bool failed = false;
            for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++)
            {
                int x = (tileX + dx + n) % n;
                int y = Mathf.Clamp(tileY + dy, 0, n - 1);
                string url = string.Format(CultureInfo.InvariantCulture,
                    "https://tile.openstreetmap.org/{0}/{1}/{2}.png", zoom, x, y);
                using var request = UnityWebRequestTexture.GetTexture(url, false);
                request.SetRequestHeader("User-Agent", "GraffitiHunterDemo/0.2");
                yield return request.SendWebRequest();
                if (request.result != UnityWebRequest.Result.Success) { failed = true; continue; }
                Texture2D tile = DownloadHandlerTexture.GetContent(request);
                mosaic.SetPixels32((dx + 1) * tileSize, (1 - dy) * tileSize, tileSize, tileSize, tile.GetPixels32());
                Destroy(tile);
            }
            mosaic.Apply();

            if (failed)
            {
                Destroy(mosaic);
                status = "Review photo - map preview unavailable";
                yield break;
            }

            float fractionX = (float)(globalTileX - Math.Floor(globalTileX));
            float fractionY = (float)(globalTileY - Math.Floor(globalTileY));
            int centerX = tileSize + Mathf.RoundToInt(fractionX * tileSize);
            int centerY = tileSize * 2 - Mathf.RoundToInt(fractionY * tileSize);
            int cropX = Mathf.Clamp(centerX - tileSize, 0, tileSize);
            int cropY = Mathf.Clamp(centerY - tileSize, 0, tileSize);
            var centeredMap = new Texture2D(tileSize * 2, tileSize * 2, TextureFormat.RGB24, false);
            centeredMap.SetPixels(mosaic.GetPixels(cropX, cropY, tileSize * 2, tileSize * 2));
            centeredMap.Apply();
            DrawMapMarker(centeredMap);
            Destroy(mosaic);
            if (mapPreview != null) Destroy(mapPreview);
            mapPreview = centeredMap;
            mapMarkerX = 0.5f;
            mapMarkerY = 0.5f;
            if (!string.IsNullOrEmpty(pendingFolder))
            {
                byte[] mapBytes = centeredMap.EncodeToPNG();
                File.WriteAllBytes(Path.Combine(pendingFolder, "map.png"), mapBytes);
                string reportId = pendingReport != null ? pendingReport.id : DateTime.UtcNow.ToString("yyyyMMdd_HHmmss");
                SaveToDcim("map_" + reportId + ".png", "image/png", mapBytes);
            }
        }

        private static bool SaveToDcim(string displayName, string mimeType, byte[] bytes)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                bool isImage = mimeType.StartsWith("image/", StringComparison.OrdinalIgnoreCase);
                string mediaClassName = isImage
                    ? "android.provider.MediaStore$Images$Media"
                    : "android.provider.MediaStore$Downloads";
                string relativePath = isImage
                    ? "DCIM/Graffiti Hunter"
                    : "Download/Graffiti Hunter";
                using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
                using (var resolver = activity.Call<AndroidJavaObject>("getContentResolver"))
                using (var media = new AndroidJavaClass(mediaClassName))
                using (var values = new AndroidJavaObject("android.content.ContentValues"))
                {
                    values.Call("put", "_display_name", displayName);
                    values.Call("put", "mime_type", mimeType);
                    values.Call("put", "relative_path", relativePath);
                    using (var pendingValue = new AndroidJavaObject("java.lang.Integer", 1))
                        values.Call("put", "is_pending", pendingValue);
                    using (var collection = media.CallStatic<AndroidJavaObject>("getContentUri", "external_primary"))
                    using (var uri = resolver.Call<AndroidJavaObject>("insert", collection, values))
                    {
                        if (uri == null) return false;
                        using (var stream = resolver.Call<AndroidJavaObject>("openOutputStream", uri))
                        {
                            if (stream == null) return false;
                            stream.Call("write", bytes);
                            stream.Call("flush");
                        }
                        values.Call("clear");
                        using (var completeValue = new AndroidJavaObject("java.lang.Integer", 0))
                            values.Call("put", "is_pending", completeValue);
                        resolver.Call<int>("update", uri, values, null, null);
                    }
                }
                return true;
            }
            catch (Exception exception)
            {
                Debug.LogWarning("Could not save capture to DCIM: " + exception.Message);
                return false;
            }
#else
            return false;
#endif
        }

        private static Texture2D RotateTextureClockwise(Texture2D source, int degrees)
        {
            degrees = ((degrees % 360) + 360) % 360;
            if (degrees == 0) return source;
            Color32[] input = source.GetPixels32();
            int sw = source.width;
            int sh = source.height;
            int ow = degrees == 180 ? sw : sh;
            int oh = degrees == 180 ? sh : sw;
            var output = new Color32[input.Length];
            for (int y = 0; y < sh; y++)
            for (int x = 0; x < sw; x++)
            {
                int destination;
                if (degrees == 90) destination = y + (sw - 1 - x) * ow;
                else if (degrees == 180) destination = (sw - 1 - x) + (sh - 1 - y) * ow;
                else destination = (sh - 1 - y) + x * ow;
                output[destination] = input[x + y * sw];
            }
            var rotated = new Texture2D(ow, oh, TextureFormat.RGB24, false);
            rotated.SetPixels32(output);
            rotated.Apply();
            Destroy(source);
            return rotated;
        }

        private static Texture2D ApplyDigitalZoom(Texture2D source, float zoom)
        {
            var target = RenderTexture.GetTemporary(source.width, source.height, 0, RenderTextureFormat.ARGB32);
            Graphics.Blit(source, target,
                new Vector2(1f / zoom, 1f / zoom),
                new Vector2(0.5f - 0.5f / zoom, 0.5f - 0.5f / zoom));
            RenderTexture previous = RenderTexture.active;
            RenderTexture.active = target;
            var output = new Texture2D(source.width, source.height, TextureFormat.RGB24, false);
            output.ReadPixels(new Rect(0, 0, source.width, source.height), 0, 0);
            output.Apply();
            RenderTexture.active = previous;
            RenderTexture.ReleaseTemporary(target);
            Destroy(source);
            return output;
        }

        private static void DrawBox(Texture2D texture, int x, int y, int width, int height, int thickness, Color color)
        {
            var horizontal = new Color[width * thickness];
            var vertical = new Color[height * thickness];
            for (int i = 0; i < horizontal.Length; i++) horizontal[i] = color;
            for (int i = 0; i < vertical.Length; i++) vertical[i] = color;
            texture.SetPixels(x, y, width, thickness, horizontal);
            texture.SetPixels(x, y + height - thickness, width, thickness, horizontal);
            texture.SetPixels(x, y, thickness, height, vertical);
            texture.SetPixels(x + width - thickness, y, thickness, height, vertical);
            texture.Apply();
        }

        private static void DrawMapMarker(Texture2D texture)
        {
            int cx = texture.width / 2;
            int cy = texture.height / 2;
            int thickness = Mathf.Max(6, texture.width / 80);
            Color32 red = new Color32(235, 22, 22, 255);

            // Target box centered on the exact GPS coordinate.
            DrawBox(texture, cx - 9, cy - 9, 18, 18, 3, red);

            // Downward arrow: shaft above the target and a solid triangular head.
            for (int y = cy + 60; y <= cy + 126; y++)
            for (int x = cx - thickness / 2; x <= cx + thickness / 2; x++)
                if (x >= 0 && x < texture.width && y >= 0 && y < texture.height)
                    texture.SetPixel(x, y, red);

            const int arrowHeight = 48;
            const int arrowHalfWidth = 42;
            for (int row = 0; row < arrowHeight; row++)
            {
                // Narrow at the GPS target and widen upward, so the point faces down.
                int halfWidth = Mathf.RoundToInt(arrowHalfWidth * row / (float)(arrowHeight - 1));
                int y = cy + 18 + row;
                for (int x = cx - halfWidth; x <= cx + halfWidth; x++)
                    if (x >= 0 && x < texture.width && y >= 0 && y < texture.height)
                        texture.SetPixel(x, y, red);
            }
            texture.Apply();
        }

        private static void DrawTimestamp(Texture2D texture, string text)
        {
            int scale = Mathf.Clamp(texture.width / 420, 3, 7);
            int glyphWidth = 5 * scale;
            int spacing = scale;
            int textWidth = text.Length * (glyphWidth + spacing) - spacing;
            int barHeight = 9 * scale;
            int startX = Mathf.Max(scale * 3, (texture.width - textWidth) / 2);
            int startY = scale * 2;

            var background = new Color32(8, 12, 16, 255);
            for (int y = 0; y < Mathf.Min(barHeight, texture.height); y++)
            for (int x = 0; x < texture.width; x++)
                texture.SetPixel(x, y, background);

            Color32 white = new Color32(255, 255, 255, 255);
            for (int i = 0; i < text.Length; i++)
            {
                string bits = TimestampGlyph(text[i]);
                for (int row = 0; row < 7; row++)
                for (int col = 0; col < 5; col++)
                {
                    if (bits[row * 5 + col] != '1') continue;
                    int px = startX + i * (glyphWidth + spacing) + col * scale;
                    int py = startY + (6 - row) * scale;
                    for (int yy = 0; yy < scale; yy++)
                    for (int xx = 0; xx < scale; xx++)
                        if (px + xx < texture.width && py + yy < texture.height)
                            texture.SetPixel(px + xx, py + yy, white);
                }
            }
            texture.Apply();
        }

        private static string TimestampGlyph(char character)
        {
            switch (character)
            {
                case '0': return "01110100011000110001100011000101110";
                case '1': return "00100011000010000100001000010001110";
                case '2': return "01110100010000100010001000100011111";
                case '3': return "11110000010000101110000010000111110";
                case '4': return "00010001100101010010111110001000010";
                case '5': return "11111100001000011110000010000111110";
                case '6': return "01110100001000011110100011000101110";
                case '7': return "11111000010001000100010000100001000";
                case '8': return "01110100011000101110100011000101110";
                case '9': return "01110100011000101111000010000101110";
                case '-': return "00000000000000011111000000000000000";
                case ':': return "00000001000010000000001000010000000";
                default: return "00000000000000000000000000000000000";
            }
        }

        private static void CalculateDestination(double lat, double lon, double bearingDegrees, double distanceMeters,
            out double targetLat, out double targetLon)
        {
            const double earthRadius = 6378137.0;
            double angularDistance = distanceMeters / earthRadius;
            double bearingRadians = bearingDegrees * Math.PI / 180.0;
            double latRadians = lat * Math.PI / 180.0;
            double lonRadians = lon * Math.PI / 180.0;
            double resultLat = Math.Asin(Math.Sin(latRadians) * Math.Cos(angularDistance) +
                                         Math.Cos(latRadians) * Math.Sin(angularDistance) * Math.Cos(bearingRadians));
            double resultLon = lonRadians + Math.Atan2(Math.Sin(bearingRadians) * Math.Sin(angularDistance) * Math.Cos(latRadians),
                Math.Cos(angularDistance) - Math.Sin(latRadians) * Math.Sin(resultLat));
            targetLat = resultLat * 180.0 / Math.PI;
            targetLon = resultLon * 180.0 / Math.PI;
        }

        private void OnGUI()
        {
            BuildStyles();
            bool landscape = Screen.width > Screen.height;
            float scale = Mathf.Max(1f, Screen.width / (landscape ? 960f : 540f));
            var oldMatrix = GUI.matrix;
            GUI.matrix = Matrix4x4.Scale(new Vector3(scale, scale, 1));
            float w = Screen.width / scale;
            float h = Screen.height / scale;

            if (queueMode)
            {
                DrawQueueReview(w, h);
                GUI.matrix = oldMatrix;
                return;
            }

            if (!reviewing)
            {
                DrawTransitionScreen(w, h);
                GUI.matrix = oldMatrix;
                return;
            }

            if (landscape)
            {
                DrawLandscapeCapture(w, h);
                GUI.matrix = oldMatrix;
                return;
            }

            GUI.color = Color.white;
            Texture feed = reviewing ? null : uprightCamera;
            if (feed != null)
            {
                var feedRect = new Rect(0, 0, w, h);
                if (digitalZoom < 0.99f)
                {
                    GUI.DrawTexture(feedRect, feed, ScaleMode.ScaleToFit);
                }
                else if (digitalZoom > 1.01f)
                {
                    float uv = 1f / digitalZoom;
                    GUI.DrawTextureWithTexCoords(feedRect, feed,
                        new Rect((1f - uv) / 2f, (1f - uv) / 2f, uv, uv));
                }
                else GUI.DrawTexture(feedRect, feed, ScaleMode.ScaleAndCrop);
            }
            else { GUI.color = new Color(0.04f, 0.07f, 0.09f); GUI.DrawTexture(new Rect(0, 0, w, h), Texture2D.whiteTexture); }

            if (reviewing && capturedPreview != null)
            {
                var photoRect = new Rect(28, 142, w - 56, h * 0.34f);
                GUI.color = Color.white;
                GUI.DrawTexture(photoRect, capturedPreview, ScaleMode.ScaleToFit);
                GUI.Label(new Rect(photoRect.x, photoRect.y + 8, photoRect.width, 28), "PHOTO", statusStyle);
                string photoCoordinates = string.Format(CultureInfo.InvariantCulture,
                    "LAT {0:F7}   LON {1:F7}", latitude, longitude);
                GUI.color = new Color(0f, 0f, 0f, 0.72f);
                GUI.DrawTexture(new Rect(photoRect.x, photoRect.yMax - 34, photoRect.width, 34), Texture2D.whiteTexture);
                GUI.color = Color.white;
                GUI.Label(new Rect(photoRect.x + 6, photoRect.yMax - 32, photoRect.width - 12, 28), photoCoordinates, labelStyle);
            }

            GUI.color = Color.white;
            if (theme != null && theme.panel != null)
            {
                GUI.DrawTexture(new Rect(-12, -8, w + 24, 153), theme.panel, ScaleMode.StretchToFill);
                GUI.DrawTexture(new Rect(-12, h - 220, w + 24, 235), theme.panel, ScaleMode.StretchToFill);
            }
            else
            {
                GUI.color = new Color(0, 0, 0, 0.68f);
                GUI.DrawTexture(new Rect(0, 0, w, 138), Texture2D.whiteTexture);
                GUI.DrawTexture(new Rect(0, h - 205, w, 205), Texture2D.whiteTexture);
            }
            GUI.color = Color.white;

            var headerTitleStyle = new GUIStyle(titleStyle) { fontSize = 25 };
            GUI.Label(new Rect(22, 14, w - 180, 45), "GRAFFITI HUNTER v0.5.6", headerTitleStyle);
            var accountStyle = new GUIStyle(buttonStyle) { fontSize = 14 };
            if (GUI.Button(new Rect(w - 150, 18, 128, 38), "ACCOUNT", accountStyle))
                OpenCityAccount();
            string gps = locationReady
                ? string.Format(CultureInfo.InvariantCulture, "{0:F6}, {1:F6}   ±{2:F0}m", latitude, longitude, accuracy)
                : "GPS: acquiring…";
            GUI.Label(new Rect(24, 62, w - 48, 28), gps, labelStyle);
            GUI.Label(new Rect(24, 92, w - 48, 28), "Location uses GPS only - compass disabled", labelStyle);

            if (!reviewing)
            {
                var nativeStyle = new GUIStyle(labelStyle) { fontSize = 14 };
                GUI.Label(new Rect(22, 106, w - 44, 32),
                    "REAL CAMERA2 LENS  •  TAP TO FOCUS", nativeStyle);
            }

            if (reviewing)
            {
                var mapRect = new Rect(28, h * 0.50f, w - 56, h * 0.23f);
                GUI.color = new Color(0.06f, 0.08f, 0.1f, 0.96f);
                GUI.DrawTexture(mapRect, Texture2D.whiteTexture);
                GUI.color = Color.white;
                // Show the centered map 1.5x closer while preserving the exact
                // coordinate at the middle of the card.
                if (mapPreview != null)
                    GUI.DrawTextureWithTexCoords(mapRect, mapPreview, new Rect(1f / 6f, 1f / 6f, 2f / 3f, 2f / 3f));
                GUI.Label(new Rect(mapRect.x, mapRect.y + 8, mapRect.width, 28), "CAPTURE LOCATION", statusStyle);
            }
            else DrawGuiBox(new Rect(w * 0.19f, h * 0.29f, w * 0.62f, h * 0.33f), 4, Color.red);
            GUI.Label(new Rect(20, h - 190, w - 40, 35), status, statusStyle);
            string primaryText = reviewing ? "APPROVE" : "CAPTURE";
            if (GUI.Button(new Rect(28, h - 142, (w - 72) * 0.62f, 70), primaryText, buttonStyle))
            {
                if (reviewing) ApproveReport();
                else BeginCapture();
            }
            if (GUI.Button(new Rect(44 + (w - 72) * 0.62f, h - 142, (w - 72) * 0.38f, 70), "RESET", buttonStyle))
                ResetCapture();
            if (!reviewing && queuedReportCount > 0)
            {
                if (GUI.Button(new Rect(52, h - 65, w - 104, 48),
                    "SUBMIT QUEUED (" + queuedReportCount + ")", buttonStyle))
                    SubmitQueuedReports();
            }
            else
                GUI.Label(new Rect(20, h - 60, w - 40, 34), reviewing ? "Approve or reset this report" : "Point, capture, then review", labelStyle);
            GUI.matrix = oldMatrix;
        }

        private void DrawTransitionScreen(float w, float h)
        {
            GUI.color = new Color(0.035f, 0.075f, 0.10f);
            GUI.DrawTexture(new Rect(0, 0, w, h), Texture2D.whiteTexture);
            GUI.color = Color.white;
            GUI.Label(new Rect(20, h * 0.38f, w - 40, 52), "GRAFFITI HUNTER v0.5.6", titleStyle);
            GUI.Label(new Rect(20, h * 0.48f, w - 40, 42), transitionMessage, statusStyle);
        }

        private void DrawQueueReview(float w, float h)
        {
            if (queueMediaFullscreen && fullscreenQueueTexture != null)
            {
                GUI.color = Color.black;
                GUI.DrawTexture(new Rect(0, 0, w, h), Texture2D.whiteTexture);
                GUI.color = Color.white;
                GUI.DrawTexture(new Rect(12, 12, w - 24, h - 92), fullscreenQueueTexture, ScaleMode.ScaleToFit);
                if (GUI.Button(new Rect(40, h - 70, w - 80, 54), "BACK TO PACKAGE", buttonStyle))
                {
                    queueMediaFullscreen = false;
                    fullscreenQueueTexture = null;
                }
                return;
            }

            GUI.color = new Color(0.05f, 0.09f, 0.12f);
            GUI.DrawTexture(new Rect(0, 0, w, h), Texture2D.whiteTexture);
            GUI.color = Color.white;
            GUI.Label(new Rect(22, 12, w - 44, 42), "SUBMISSION QUEUE", titleStyle);
            if (queuedReports.Count < 1)
            {
                GUI.Label(new Rect(20, 100, w - 40, 40), "No approved packages", statusStyle);
                if (GUI.Button(new Rect(40, h - 80, w - 80, 55), "BACK", buttonStyle)) ReturnToNativeHome();
                return;
            }

            Report report = queuedReports[queueIndex];
            string itemNumber = (queueIndex + 1) + " of " + queuedReports.Count;
            GUI.Label(new Rect(20, 55, w - 40, 30), itemNumber + "   " + report.id, labelStyle);

            float previewY = 90;
            float previewWidth = (w - 66) / 2f;
            Rect queuePhotoRect = new Rect(22, previewY, previewWidth, 180);
            Rect queueMapRect = new Rect(44 + previewWidth, previewY, previewWidth, 180);
            if (queuePhoto != null)
                GUI.DrawTexture(queuePhotoRect, queuePhoto, ScaleMode.ScaleToFit);
            if (queueMap != null)
                GUI.DrawTexture(queueMapRect, queueMap, ScaleMode.ScaleAndCrop);
            if (Event.current.type == EventType.MouseUp)
            {
                if (queuePhoto != null && queuePhotoRect.Contains(Event.current.mousePosition))
                {
                    fullscreenQueueTexture = queuePhoto;
                    queueMediaFullscreen = true;
                    Event.current.Use();
                }
                else if (queueMap != null && queueMapRect.Contains(Event.current.mousePosition))
                {
                    fullscreenQueueTexture = queueMap;
                    queueMediaFullscreen = true;
                    Event.current.Use();
                }
            }

            string details = string.Format(CultureInfo.InvariantCulture,
                "LAT {0:F7}   LON {1:F7}\nCaptured {2}\nGPS accuracy Â±{3:F0}m",
                report.estimatedTargetLatitude, report.estimatedTargetLongitude,
                report.capturedUtc, report.horizontalAccuracyMeters);
            GUI.Label(new Rect(18, 278, w - 36, 75), details, labelStyle);
            GUI.Label(new Rect(20, 353, w - 40, 30), "WHERE IS THE GRAFFITI?", statusStyle);

            var categoryStyle = new GUIStyle(buttonStyle) { fontSize = 15 };
            queueCategoryIndex = GUI.SelectionGrid(new Rect(24, 388, w - 48, 250),
                queueCategoryIndex, LocationTypes, 2, categoryStyle);
            report.locationType = LocationTypes[queueCategoryIndex];
            RefreshSubmissionDescription(report);

            if (GUI.Button(new Rect(28, 650, (w - 72) / 2f, 52),
                "OFFENSIVE: " + report.offensive, categoryStyle))
                report.offensive = report.offensive == "Yes" ? "No" : "Yes";
            if (GUI.Button(new Rect(44 + (w - 72) / 2f, 650, (w - 72) / 2f, 52),
                "UPDATE PACKAGE", categoryStyle))
                UpdateQueuePackage();

            GUI.Label(new Rect(20, 710, w - 40, 55), report.suggestedDescription, labelStyle);
            if (GUI.Button(new Rect(28, 772, (w - 72) / 2f, 52),
                "MARK PROCESSED", categoryStyle))
                MarkQueuePackageProcessed();
            if (GUI.Button(new Rect(44 + (w - 72) / 2f, 772, (w - 72) / 2f, 52),
                queueDeleteConfirm ? "CONFIRM DELETE" : "DELETE / REJECT", categoryStyle))
            {
                if (queueDeleteConfirm) DeleteQueuePackage();
                else queueDeleteConfirm = true;
            }
            if (queueDeleteConfirm)
                GUI.Label(new Rect(20, 828, w - 40, 32),
                    "Tap CONFIRM DELETE again to permanently remove this package.", labelStyle);
            if (GUI.Button(new Rect(28, h - 190, w - 56, 64), "PREPARE GET IT DONE", buttonStyle))
                PrepareQueuedWebsite();

            if (GUI.Button(new Rect(28, h - 112, 120, 54), "< PREV", categoryStyle))
            {
                queueIndex = (queueIndex - 1 + queuedReports.Count) % queuedReports.Count;
                LoadQueuePreview();
            }
            if (GUI.Button(new Rect(w - 148, h - 112, 120, 54), "NEXT >", categoryStyle))
            {
                queueIndex = (queueIndex + 1) % queuedReports.Count;
                LoadQueuePreview();
            }
            if (GUI.Button(new Rect((w - 150) / 2f, h - 112, 150, 54), "BACK", categoryStyle))
            {
                ReturnToNativeHome();
            }
        }

        private void DrawLandscapeCapture(float w, float h)
        {
            GUI.color = Color.white;
            Texture feed = reviewing ? capturedPreview : uprightCamera;
            if (feed != null)
            {
                if (!reviewing && digitalZoom < 0.99f)
                {
                    GUI.DrawTexture(new Rect(0, 0, w, h), feed, ScaleMode.ScaleToFit);
                }
                else if (!reviewing && digitalZoom > 1.01f)
                {
                    float uv = 1f / digitalZoom;
                    GUI.DrawTextureWithTexCoords(new Rect(0, 0, w, h), feed,
                        new Rect((1f - uv) / 2f, (1f - uv) / 2f, uv, uv));
                }
                else GUI.DrawTexture(new Rect(0, 0, w, h), feed, ScaleMode.ScaleAndCrop);
            }
            else
            {
                GUI.color = new Color(0.04f, 0.07f, 0.09f);
                GUI.DrawTexture(new Rect(0, 0, w, h), Texture2D.whiteTexture);
            }

            GUI.color = new Color(0f, 0f, 0f, 0.62f);
            GUI.DrawTexture(new Rect(0, 0, w, 70), Texture2D.whiteTexture);
            GUI.DrawTexture(new Rect(0, h - 78, w, 78), Texture2D.whiteTexture);
            GUI.color = Color.white;
            var landscapeTitleStyle = new GUIStyle(titleStyle) { fontSize = 25 };
            GUI.Label(new Rect(20, 7, 330, 42), "GRAFFITI HUNTER v0.5.6", landscapeTitleStyle);
            GUI.Label(new Rect(320, 12, 390, 28),
                string.Format(CultureInfo.InvariantCulture, "{0:F6}, {1:F6}  GPS",
                    latitude, longitude), labelStyle);

            if (!reviewing)
            {
                DrawGuiBox(new Rect(w * 0.31f, h * 0.22f, w * 0.38f, h * 0.48f), 4f, Color.red);
                if (GUI.Button(new Rect(24, h - 66, w - 48, 54),
                    "OPEN REAL CAMERA", buttonStyle))
                    BeginCapture();
            }
            else
            {
                if (GUI.Button(new Rect(24, h - 66, (w - 72) * 0.62f, 54), "APPROVE", buttonStyle))
                    ApproveReport();
                if (GUI.Button(new Rect(48 + (w - 72) * 0.62f, h - 66, (w - 72) * 0.38f, 54), "RESET", buttonStyle))
                    ResetCapture();
            }
        }

        private void BuildStyles()
        {
            if (titleStyle != null) return;
            titleStyle = new GUIStyle(GUI.skin.label) { fontSize = 28, fontStyle = FontStyle.Bold, alignment = TextAnchor.MiddleLeft, normal = { textColor = Color.white } };
            labelStyle = new GUIStyle(GUI.skin.label) { fontSize = 17, alignment = TextAnchor.MiddleCenter, normal = { textColor = Color.white } };
            statusStyle = new GUIStyle(labelStyle) { fontSize = 20, fontStyle = FontStyle.Bold };
            buttonStyle = new GUIStyle(GUI.skin.button) { fontSize = 24, fontStyle = FontStyle.Bold, normal = { textColor = Color.white }, active = { textColor = Color.white } };
            if (theme != null && theme.button != null)
            {
                buttonStyle.normal.background = theme.button;
                buttonStyle.hover.background = theme.button;
                buttonStyle.active.background = theme.buttonDown != null ? theme.buttonDown : theme.button;
                buttonStyle.border = new RectOffset(34, 34, 24, 24);
            }
            else
            {
                buttonStyle.normal.background = MakeTexture(new Color(0.78f, 0.05f, 0.05f));
                buttonStyle.active.background = MakeTexture(new Color(0.5f, 0.02f, 0.02f));
            }
        }

        private static Texture2D MakeTexture(Color color)
        {
            var texture = new Texture2D(1, 1);
            texture.SetPixel(0, 0, color);
            texture.Apply();
            return texture;
        }

        private static void DrawGuiBox(Rect rect, float thickness, Color color)
        {
            Color previous = GUI.color;
            GUI.color = color;
            GUI.DrawTexture(new Rect(rect.x, rect.y, rect.width, thickness), Texture2D.whiteTexture);
            GUI.DrawTexture(new Rect(rect.x, rect.yMax - thickness, rect.width, thickness), Texture2D.whiteTexture);
            GUI.DrawTexture(new Rect(rect.x, rect.y, thickness, rect.height), Texture2D.whiteTexture);
            GUI.DrawTexture(new Rect(rect.xMax - thickness, rect.y, thickness, rect.height), Texture2D.whiteTexture);
            GUI.color = previous;
        }

        private static void DrawGuiArrow(Vector2 point)
        {
            Color previous = GUI.color;
            GUI.color = Color.red;
            GUI.DrawTexture(new Rect(point.x - 5, point.y - 70, 10, 42), Texture2D.whiteTexture);
            // Solid downward triangle, drawn as horizontal bars so it remains
            // unmistakably an arrow on every Android GPU.
            for (int row = 0; row < 18; row++)
            {
                float halfWidth = 22f - row * 1.15f;
                GUI.DrawTexture(new Rect(point.x - halfWidth, point.y - 30 + row, halfWidth * 2f, 2f), Texture2D.whiteTexture);
            }
            GUI.color = previous;
        }

        private static string Cardinal(float degrees)
        {
            string[] names = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
            return names[Mathf.RoundToInt(degrees / 45f) % 8];
        }

        private static string CardinalWords(float degrees)
        {
            string[] names =
            {
                "north", "northeast", "east", "southeast",
                "south", "southwest", "west", "northwest"
            };
            return names[Mathf.RoundToInt(degrees / 45f) % 8];
        }

        private void OnDestroy()
        {
            if (cameraTexture != null) cameraTexture.Stop();
            if (uprightCamera != null) uprightCamera.Release();
            if (cameraRotateMaterial != null) Destroy(cameraRotateMaterial);
            if (Input.location.status == LocationServiceStatus.Running) Input.location.Stop();
#if UNITY_ANDROID && !UNITY_EDITOR
            try { if (sensorManager != null && sensorListener != null) sensorManager.Call("unregisterListener", sensorListener); } catch { }
            try { speechRecognizer?.Call("destroy"); } catch { }
#endif
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        private sealed class RotationVectorListener : AndroidJavaProxy
        {
            private readonly GraffitiHunterApp owner;
            public RotationVectorListener(GraffitiHunterApp owner) : base("android.hardware.SensorEventListener") { this.owner = owner; }
            public void onAccuracyChanged(AndroidJavaObject sensor, int accuracy) { }
            public void onSensorChanged(AndroidJavaObject sensorEvent)
            {
                owner.UpdateNativeHeading(sensorEvent.Get<float[]>("values"));
            }
        }

        private sealed class SpeechListener : AndroidJavaProxy
        {
            private readonly GraffitiHunterApp owner;
            public SpeechListener(GraffitiHunterApp owner) : base("android.speech.RecognitionListener") { this.owner = owner; }
            public void onReadyForSpeech(AndroidJavaObject parameters) { }
            public void onBeginningOfSpeech() { }
            public void onRmsChanged(float rmsdB) { }
            public void onBufferReceived(byte[] buffer) { }
            public void onEndOfSpeech() { owner.VoiceSessionEnded(); }
            public void onError(int error) { owner.VoiceSessionEnded(); }
            public void onResults(AndroidJavaObject results) { owner.ProcessSpeechResults(results); }
            public void onPartialResults(AndroidJavaObject partialResults) { owner.ProcessSpeechResults(partialResults); }
            public void onEvent(int eventType, AndroidJavaObject parameters) { }
        }
#endif
    }
}
