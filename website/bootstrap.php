<?php
if(session_status() !== PHP_SESSION_ACTIVE) {
  session_set_cookie_params(0, '/', '', !empty($_SERVER['HTTPS']), true);
  session_start();
}
$configFile = dirname(__DIR__) . '/.graffitihunter-config.php';
if(!is_file($configFile)) throw new RuntimeException('Private configuration is unavailable.');
$db = require $configFile;
$pdo = new PDO(
  'mysql:host=' . $db['host'] . ';dbname=' . $db['name'] . ';charset=utf8mb4',
  $db['user'], $db['pass'],
  array(PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC, PDO::ATTR_EMULATE_PREPARES=>false)
);
$pdo->exec("CREATE TABLE IF NOT EXISTS gh_users (
  id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  email VARCHAR(190) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login_at TIMESTAMP NULL DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
$pdo->exec("CREATE TABLE IF NOT EXISTS gh_walk_tracks (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id INT UNSIGNED NOT NULL,
  title VARCHAR(160) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  point_count INT UNSIGNED NOT NULL DEFAULT 0,
  started_at DATETIME NULL,
  ended_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX(user_id), CONSTRAINT fk_gh_walk_user FOREIGN KEY(user_id) REFERENCES gh_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
$alterations=array("ALTER TABLE gh_walk_tracks ADD COLUMN city_id INT UNSIGNED NULL AFTER user_id","ALTER TABLE gh_walk_tracks ADD COLUMN file_hash CHAR(64) NULL AFTER original_filename","ALTER TABLE gh_walk_tracks ADD UNIQUE KEY uq_walk_user_hash(user_id,file_hash)","ALTER TABLE gh_walk_tracks ADD COLUMN matched_geojson MEDIUMTEXT NULL","ALTER TABLE gh_walk_tracks ADD COLUMN match_status VARCHAR(30) NOT NULL DEFAULT 'pending'","ALTER TABLE gh_walk_tracks ADD COLUMN filtered_point_count INT UNSIGNED NOT NULL DEFAULT 0");
foreach($alterations as $sql){try{$pdo->exec($sql);}catch(PDOException $ignored){}}
$pdo->exec("CREATE TABLE IF NOT EXISTS gh_cities (id INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,slug VARCHAR(80) NOT NULL UNIQUE,name VARCHAR(120) NOT NULL,region VARCHAR(120) NOT NULL,country_code CHAR(2) NOT NULL DEFAULT 'US',min_lat DECIMAL(10,7) NOT NULL,max_lat DECIMAL(10,7) NOT NULL,min_lon DECIMAL(11,7) NOT NULL,max_lon DECIMAL(11,7) NOT NULL,center_lat DECIMAL(10,7) NOT NULL,center_lon DECIMAL(11,7) NOT NULL,default_zoom DECIMAL(4,1) NOT NULL DEFAULT 11.0,enabled TINYINT(1) NOT NULL DEFAULT 1) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
$pdo->exec("INSERT IGNORE INTO gh_cities(slug,name,region,min_lat,max_lat,min_lon,max_lon,center_lat,center_lon,default_zoom) VALUES('san-diego','San Diego','California',32.5300000,33.1200000,-117.3000000,-116.9000000,32.7157000,-117.1611000,11.0)");
$pdo->exec("CREATE TABLE IF NOT EXISTS gh_walk_points (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,track_id BIGINT UNSIGNED NOT NULL,sequence_no INT UNSIGNED NOT NULL,recorded_at DATETIME(3) NULL,latitude DECIMAL(10,7) NOT NULL,longitude DECIMAL(11,7) NOT NULL,accuracy_m DECIMAL(8,2) NULL,provider VARCHAR(30) NULL,UNIQUE KEY uq_track_sequence(track_id,sequence_no),INDEX idx_track(track_id),INDEX idx_lat_lon(latitude,longitude),CONSTRAINT fk_gh_point_track FOREIGN KEY(track_id) REFERENCES gh_walk_tracks(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
$pdo->exec("CREATE TABLE IF NOT EXISTS gh_api_tokens (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id INT UNSIGNED NOT NULL,token_hash CHAR(64) NOT NULL UNIQUE,label VARCHAR(100) NOT NULL DEFAULT 'Android phone',created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,last_used_at TIMESTAMP NULL DEFAULT NULL,revoked_at TIMESTAMP NULL DEFAULT NULL,INDEX(user_id),CONSTRAINT fk_gh_token_user FOREIGN KEY(user_id) REFERENCES gh_users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
$pdo->exec("CREATE TABLE IF NOT EXISTS gh_pairing_codes (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id INT UNSIGNED NOT NULL,code_hash CHAR(64) NOT NULL UNIQUE,expires_at DATETIME NOT NULL,used_at DATETIME NULL DEFAULT NULL,created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,INDEX(user_id),CONSTRAINT fk_gh_pair_user FOREIGN KEY(user_id) REFERENCES gh_users(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
function gh_redirect($message, $type, $location) {
  $_SESSION['gh_flash'] = $message; $_SESSION['gh_flash_type'] = $type;
  header('Location: ' . $location); exit;
}
