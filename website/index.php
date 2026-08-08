<?php
session_start();
$signedIn = !empty($_SESSION['gh_user_id']);
$flash = isset($_SESSION['gh_flash']) ? $_SESSION['gh_flash'] : '';
$flashType = isset($_SESSION['gh_flash_type']) ? $_SESSION['gh_flash_type'] : 'info';
unset($_SESSION['gh_flash'], $_SESSION['gh_flash_type']);
$token = bin2hex(random_bytes(24));
$_SESSION['gh_csrf'] = $token;
?>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="theme-color" content="#07130f">
  <title>Graffiti Hunter | Capture. Map. Improve.</title>
  <meta name="description" content="Graffiti Hunter packages field photos, precise locations, maps, and report details for faster community cleanup reporting.">
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@600;700;800&amp;family=Inter:wght@400;500;600;700&amp;display=swap" rel="stylesheet">
  <link rel="stylesheet" href="assets/site.css">
</head>
<body>
  <header class="site-header">
    <a class="brand" href="#top" aria-label="Graffiti Hunter home">
      <span class="brand-mark" aria-hidden="true">GH</span>
      <span>GRAFFITI HUNTER <small>FIELD REPORTING</small></span>
    </a>
    <nav aria-label="Main navigation">
      <a href="#how">How it works</a>
      <a href="#tracker">Walk tracker</a>
      <a class="nav-login" href="#login"><?php echo $signedIn ? 'Dashboard' : 'Log in'; ?></a>
    </nav>
  </header>

  <main id="top">
    <section class="hero">
      <div class="hero-grid" aria-hidden="true"></div>
      <div class="spray spray-one" aria-hidden="true"></div>
      <div class="spray spray-two" aria-hidden="true"></div>
      <div class="hero-copy">
        <p class="eyebrow"><span></span> Built in San Diego · Beta field project</p>
        <h1>See it.<br><em>Map it.</em><br>Get it done.</h1>
        <p class="hero-intro">A home-made Android field tool that turns every graffiti photo into an organized reporting package—with GPS, map, timestamp, address, and evidence ready for review.</p>
        <div class="hero-actions">
          <a class="button primary" href="#login">Open your tracker</a>
          <a class="button ghost" href="#how">Explore the process</a>
        </div>
        <div class="hero-stats">
          <div><strong>10 sec</strong><span>Walk GPS samples</span></div>
          <div><strong>1 tap</strong><span>Capture workflow</span></div>
          <div><strong>Local first</strong><span>You control the queue</span></div>
        </div>
      </div>
      <div class="hero-visual" aria-label="Illustration of a mapped graffiti report">
        <div class="phone">
          <div class="phone-top"><span></span>GRAFFITI HUNTER <b>0.5.6</b></div>
          <div class="camera-scene">
            <div class="wall-lines"></div>
            <div class="graffiti-word">HUNT</div>
            <div class="target-box"></div>
          </div>
          <div class="phone-controls"><span>GPS READY</span><button type="button" tabindex="-1">CAPTURE</button></div>
        </div>
        <div class="map-card">
          <span class="map-label">REPORT PACKAGE</span>
          <div class="map-lines"></div><div class="map-road road-a"></div><div class="map-road road-b"></div>
          <div class="pin"><i></i></div>
          <p>32.7157° N<br>117.1611° W</p>
        </div>
      </div>
    </section>

    <section class="process" id="how">
      <div class="section-heading"><p class="eyebrow dark"><span></span> From sidewalk to submission</p><h2>A cleaner reporting workflow.</h2><p>Capture quickly in the field. Make careful decisions later.</p></div>
      <div class="steps">
        <article><b>01</b><div class="step-icon">◎</div><h3>Capture</h3><p>Use the phone camera or connected glasses. Frame the issue with the on-screen red target.</p></article>
        <article><b>02</b><div class="step-icon">⌖</div><h3>Package</h3><p>Pair the original image with map, coordinates, timestamp, address, and category.</p></article>
        <article><b>03</b><div class="step-icon">✓</div><h3>Review</h3><p>Approve, edit, or delete each queued report. Nothing leaves your control automatically.</p></article>
        <article><b>04</b><div class="step-icon">↗</div><h3>Prepare</h3><p>Pre-fill the reporting site with the package. You inspect everything before final submission.</p></article>
      </div>
    </section>

    <section class="tracker" id="tracker">
      <div class="tracker-art" aria-hidden="true">
        <div class="route"></div><span class="route-dot one"></span><span class="route-dot two"></span><span class="route-dot three"></span><span class="route-dot four"></span>
        <div class="tracker-chip">WALK TRACK · 42 POINTS</div>
      </div>
      <div class="tracker-copy"><p class="eyebrow"><span></span> Coming next</p><h2>Turn a walk into a map.</h2><p>The Android app already records timestamped latitude and longitude samples every ten seconds. The private tracker will import those CSV files and draw your route, capture points, and field-work history on an interactive map.</p><ul><li>Private route history</li><li>Capture locations layered on the walk</li><li>CSV import and map export</li><li>Submission progress at a glance</li></ul></div>
    </section>

    <section class="login-section" id="login">
      <div class="login-message"><p class="eyebrow dark"><span></span> Private field dashboard</p><h2>Keep the hunt organized.</h2><p>Sign in to save and track your work. New team member? Create an account below—the dashboard foundation is ready for the upcoming map importer.</p><div class="privacy-note"><b>Your data stays yours.</b><br>Passwords are securely hashed. Captures are never submitted without your approval.</div></div>
      <div class="auth-card">
        <?php if($flash): ?><div class="flash <?php echo htmlspecialchars($flashType, ENT_QUOTES, 'UTF-8'); ?>"><?php echo htmlspecialchars($flash, ENT_QUOTES, 'UTF-8'); ?></div><?php endif; ?>
        <?php if($signedIn): ?>
          <h3>Welcome back</h3><p>You are signed in and ready to review your field dashboard.</p>
          <a class="button primary full" href="dashboard.php">Open dashboard</a>
          <a class="text-link" href="logout.php">Sign out</a>
        <?php else: ?>
          <div class="auth-tabs" role="tablist"><button class="active" data-panel="login-panel" type="button">Log in</button><button data-panel="register-panel" type="button">Create account</button></div>
          <form id="login-panel" class="auth-panel active" action="auth.php" method="post">
            <input type="hidden" name="csrf" value="<?php echo $token; ?>"><input type="hidden" name="action" value="login">
            <label>Email address<input type="email" name="email" autocomplete="email" required></label>
            <label>Password<input type="password" name="password" autocomplete="current-password" required></label>
            <button class="button primary full" type="submit">Log in securely</button>
          </form>
          <form id="register-panel" class="auth-panel" action="auth.php" method="post">
            <input type="hidden" name="csrf" value="<?php echo $token; ?>"><input type="hidden" name="action" value="register">
            <label>Your name<input type="text" name="name" maxlength="100" autocomplete="name" required></label>
            <label>Email address<input type="email" name="email" autocomplete="email" required></label>
            <label>Password <small>12 characters minimum</small><input type="password" name="password" minlength="12" autocomplete="new-password" required></label>
            <button class="button primary full" type="submit">Create private account</button>
          </form>
        <?php endif; ?>
      </div>
    </section>
  </main>
  <footer><a class="brand" href="#top"><span class="brand-mark">GH</span><span>GRAFFITI HUNTER</span></a><p>Graffiti Hunter Build 0.5.6 Beta · Home-made in San Diego.</p><p>This independent project is not affiliated with the City of San Diego.</p></footer>
  <script src="assets/site.js"></script>
</body>
</html>
