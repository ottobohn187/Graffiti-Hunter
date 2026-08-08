document.querySelectorAll('.auth-tabs button').forEach(function(button){
  button.addEventListener('click',function(){
    document.querySelectorAll('.auth-tabs button').forEach(function(item){item.classList.remove('active');});
    document.querySelectorAll('.auth-panel').forEach(function(panel){panel.classList.remove('active');});
    button.classList.add('active');
    document.getElementById(button.getAttribute('data-panel')).classList.add('active');
  });
});
