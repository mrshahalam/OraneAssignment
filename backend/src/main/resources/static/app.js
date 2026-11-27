const app={token:localStorage.getItem('token'),role:localStorage.getItem('role')};
const $=(q)=>document.querySelector(q);
async function api(path,options={}){
  const res=await fetch(path,{...options,headers:{'Authorization':'Bearer '+app.token,'Content-Type':'application/json',...(options.headers||{})}});
  if(!res.ok){const body=await res.json().catch(()=>({message:res.statusText}));throw new Error(body.message||'Request failed');}
  if(res.status===204)return null;
  return res.json();
}
function table(rows,columns){
  if(!rows?.length)return '<p class="muted">No records found.</p>';
  return '<table class="table"><thead><tr>'+columns.map(c=>'<th>'+c[0]+'</th>').join('')+'</tr></thead><tbody>'+
    rows.map(r=>'<tr>'+columns.map(c=>'<td>'+((r[c[1]]??'')+'').replaceAll('<','&lt;')+'</td>').join('')+'</tr>').join('')+'</tbody></table>';
}
$('#login-form').onsubmit=async e=>{
 e.preventDefault();$('#login-error').textContent='';
 const data=Object.fromEntries(new FormData(e.target));
 try{const r=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)}).then(async x=>{if(!x.ok)throw new Error((await x.json()).message);return x.json()});
 app.token=r.token;app.role=r.role;localStorage.setItem('token',r.token);localStorage.setItem('role',r.role);showShell();
 }catch(err){$('#login-error').textContent=err.message}
};
$('#logout').onclick=()=>{localStorage.clear();location.reload()};
function showShell(){
 $('#login-view').hidden=true;$('#shell').hidden=false;$('#role').textContent=app.role;
 const links=app.role==='HR_ADMIN'?[['Employees','employees'],['Departments','departments'],['Positions','positions'],['Imports','imports'],['Leaves','leaves'],['Workflow','workflow']]
 :app.role==='MANAGER'?[['Team','employees'],['Leaves','leaves']]:[['My profile','employees'],['Leave','leaves']];
 $('#nav').innerHTML=links.map(x=>'<button data-view="'+x[1]+'">'+x[0]+'</button>').join('');
 $('#nav').onclick=e=>{if(e.target.dataset.view)render(e.target.dataset.view)};
 render(app.role==='HR_ADMIN'?'employees':app.role==='MANAGER'?'employees':'employees');
}
async function render(view){
 $('#content').innerHTML='Loading...';
 try{
  if(view==='employees'){
   const r=await api('/api/employees?size=100');$('#content').innerHTML='<h2>Employees</h2>'+table(r.content,[['Code','employee_code'],['Name','first_name'],['Email','email'],['Status','employment_status']]);
  }else if(view==='departments'){
   const r=await api('/api/departments');$('#content').innerHTML='<h2>Departments</h2>'+table(r,[['Code','code'],['Name','name'],['Active employees','active_employees']]);
  }else if(view==='positions'){
   const r=await api('/api/positions');$('#content').innerHTML='<h2>Positions</h2>'+table(r,[['Code','code'],['Title','title'],['Department','department_name'],['Assigned','active_employee_name']]);
  }else if(view==='imports'){
   const r=await api('/api/imports');$('#content').innerHTML='<h2>Bulk imports</h2><form id="upload"><input type="file" name="file" accept=".csv" required><button>Upload CSV</button></form><p id="msg"></p>'+table(r,[['File','file_name'],['Status','status'],['Total','total_rows'],['Success','success_rows'],['Failed','failed_rows']]);
   $('#upload').onsubmit=async e=>{e.preventDefault();const f=new FormData(e.target);try{await fetch('/api/imports',{method:'POST',headers:{Authorization:'Bearer '+app.token},body:f}).then(async x=>{if(!x.ok)throw new Error((await x.json()).message)});$('#msg').textContent='Import queued';setTimeout(()=>render('imports'),1500)}catch(err){$('#msg').textContent=err.message}}
  }else if(view==='leaves'){
   const [types,reqs]=await Promise.all([api('/api/leaves/types'),api('/api/leaves')]);
   $('#content').innerHTML='<h2>Leave requests</h2><div class="grid"><form id="leave-form"><h3>Apply</h3><label>Type<select name="leaveTypeId">'+types.map(t=>'<option value="'+t.id+'">'+t.name+'</option>').join('')+'</select></label><label>Start<input name="startDate" type="date" required></label><label>End<input name="endDate" type="date" required></label><label>Reason<textarea name="reason" required></textarea></label><button>Submit</button></form></div><p id="leave-msg"></p>'+table(reqs,[['Employee','employee_code'],['Type','leave_type_code'],['Start','start_date'],['End','end_date'],['Status','status'],['ID','id']]);
   $('#leave-form').onsubmit=async e=>{e.preventDefault();const d=Object.fromEntries(new FormData(e.target));d.leaveTypeId=Number(d.leaveTypeId);try{await api('/api/leaves',{method:'POST',body:JSON.stringify(d)});$('#leave-msg').textContent='Leave submitted';render('leaves')}catch(err){$('#leave-msg').textContent=err.message}};
  }else if(view==='workflow'){
   const r=await api('/api/workflows');$('#content').innerHTML='<h2>Approval workflow</h2>'+table(r,[['Name','name'],['Active','active']]);
  }
 }catch(err){$('#content').innerHTML='<p class="error">'+err.message+'</p>'}
}
if(app.token&&app.role)showShell();
