<?php
require __DIR__ . '/bootstrap.php';
if($_SERVER['REQUEST_METHOD'] !== 'POST') gh_redirect('Invalid request.', 'error', 'index.php#login');
if(empty($_POST['csrf']) || empty($_SESSION['gh_csrf']) || !hash_equals($_SESSION['gh_csrf'], $_POST['csrf'])) gh_redirect('Your session expired. Please try again.', 'error', 'index.php#login');
$email = strtolower(trim(isset($_POST['email']) ? $_POST['email'] : ''));
$password = isset($_POST['password']) ? $_POST['password'] : '';
if(!filter_var($email, FILTER_VALIDATE_EMAIL)) gh_redirect('Enter a valid email address.', 'error', 'index.php#login');
if(isset($_POST['action']) && $_POST['action'] === 'register') {
  $name = trim(isset($_POST['name']) ? $_POST['name'] : '');
  if(strlen($name) < 2 || strlen($name) > 100) gh_redirect('Enter your name.', 'error', 'index.php#login');
  if(strlen($password) < 12) gh_redirect('Use a password with at least 12 characters.', 'error', 'index.php#login');
  $exists = $pdo->prepare('SELECT id FROM gh_users WHERE email=?'); $exists->execute(array($email));
  if($exists->fetch()) gh_redirect('An account already exists for that email.', 'error', 'index.php#login');
  $add = $pdo->prepare('INSERT INTO gh_users(name,email,password_hash) VALUES(?,?,?)');
  $add->execute(array($name,$email,password_hash($password,PASSWORD_DEFAULT)));
  session_regenerate_id(true); $_SESSION['gh_user_id'] = (int)$pdo->lastInsertId(); $_SESSION['gh_user_name'] = $name;
  gh_redirect('Account created. Welcome to Graffiti Hunter.', 'success', 'dashboard.php');
}
$find = $pdo->prepare('SELECT id,name,password_hash FROM gh_users WHERE email=?'); $find->execute(array($email)); $user = $find->fetch();
if(!$user || !password_verify($password,$user['password_hash'])) { usleep(350000); gh_redirect('Email or password was not recognized.', 'error', 'index.php#login'); }
session_regenerate_id(true); $_SESSION['gh_user_id']=(int)$user['id']; $_SESSION['gh_user_name']=$user['name'];
$pdo->prepare('UPDATE gh_users SET last_login_at=NOW() WHERE id=?')->execute(array($user['id']));
header('Location: dashboard.php');
