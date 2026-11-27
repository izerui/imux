const $=s=>document.querySelector(s);
const modes={session:['推荐先做','让任务留在你正在聊的地方','不另开工作台。任务附着在当前会话，查看结果时再展开详情。','关联当前会话 → 模拟推进 → 确认 → 查看结果'],task:['更容易管理多件事','先看到事情，再找到负责的会话','任务与会话是两个视角。换执行者不换任务，历史仍然可以追溯。','新任务 → 新会话执行 → 交接给 Pi → 继续推进'],team:['后续高级模式','你跟主会话聊，它负责委派','只有你确认协作方案后才创建工作会话。工作目录隔离，结果回到主会话。','创建协作任务 → 批准委派 → 查看子任务 → 整体验证']};
const requested=new URLSearchParams(location.search).get('mode')||location.pathname.split('/').pop().replace('.html','');
const mode=requested in modes?requested:'session';
document.body.className=mode;const meta=modes[mode];['eyebrow','heading','description','path'].forEach((id,i)=>$('#'+id).textContent=meta[i]);document.querySelector(`nav a[href="?mode=${mode}"]`).setAttribute('aria-current','page');
let task=null,agent='Codex',session=1,count=1,view=mode==='session'?'sessions':'tasks',details=mode!=='session',messages=[],history=[],phase=0,paused=false,closed=false,delegated=false,childrenStopped=false;
const steps=['正在定位','正在修改','等待确认','正在验证','等待设备验证','已验收'];
const escapeHtml=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const button=(label,action,cls='')=>`<button class="${cls}" data-action="${action}">${label}</button>`;
function notify(text){$('#notice').textContent=text;}
function add(who,text){messages.push({who,text});}
function dialog(title,body,label,action){$('#modal-title').textContent=title;$('#modal-body').innerHTML=body;$('#confirm').textContent=label;$('#confirm').onclick=()=>{if(action()!==false)$('#modal').close();};$('#modal').showModal();}
$('#cancel').onclick=()=>$('#modal').close();
function state(){return closed?'会话已关闭':paused?'已停止':steps[phase];}
function render(){
 $('#tasks').classList.toggle('active',view==='tasks');$('#sessions').classList.toggle('active',view==='sessions');
 $('#list').innerHTML=view==='tasks'?`<div class="sectionlabel">当前工程 · ${task?1:0} 项任务</div>${task?`<button class="item selected" data-action="focus">${escapeHtml(task)}<small>${state()} · ${agent}</small></button>`:'<p style="padding:0 16px">还没有任务。直接在当前会话里开始，或新建任务。</p>'}`:`<div class="sectionlabel">当前负责</div><button class="item selected" data-action="focus">${agent} · 会话 ${session}<small>${closed?'已关闭，可恢复':task?escapeHtml(task):'正在讨论手机通知'}</small></button>${history.map(x=>`<button class="item" data-action="history">${x.agent} · 会话 ${x.id}<small>已交接 · 不再执行</small></button>`).join('')}${delegated?'<div class="sectionlabel">已授权的工作会话</div><button class="item" data-action="child">Codex · 后端处理<small>隔离目录 backend</small></button><button class="item" data-action="child">Pi · 回归测试<small>隔离目录 tests</small></button>':''}`;
 $('#sessiontab').textContent=`${agent} · 会话 ${session}`;
 $('#progress').innerHTML=task?`<div class="progress"><div><strong>${escapeHtml(task)}</strong><p><span class="pill">${state()}</span>　${agent} 主会话负责${delegated?' · 2 个子任务':''}</p></div>${button(details?'收起详情':'查看详情','details')}</div>`:'<div class="progress"><div><strong>继续当前讨论，不必另起会话</strong><p>任务将关联此会话；不会自动新建或委派。</p></div>'+button('将这件事作为任务','attach','primary')+'</div>';
 $('#conversation').innerHTML=messages.map(m=>`<div class="bubble ${m.who==='你'?'user':''}"><div class="who">${escapeHtml(m.who)}</div><div>${escapeHtml(m.text).replace(/\n/g,'<br>')}</div></div>`).join('');
 $('#inspector').hidden=!details;$('.workspace').style.gridTemplateColumns=details?'':(innerWidth>1100?'260px minmax(320px,1fr)':'');
 $('#inspector').innerHTML=`<h2>任务详情 <span class="pill">模拟</span></h2>${task?`<h3>目标</h3><p>${escapeHtml(task)}</p><h3>谁负责这件事</h3><div class="node">${agent} · 主会话 ${session}<small>${closed?'会话未运行，可显式恢复':'唯一的主负责人'}${history.length?' · 有交接记录':''}</small></div>${delegated?`<div class="node">Codex · 后端处理<small>${childrenStopped?'已停止':phase>=4?'已返回改动，等待整体验收':'隔离目录 backend · 模拟运行'}</small></div><div class="node">Pi · 回归测试<small>${childrenStopped?'已停止':phase>=4?'已返回测试记录':'隔离目录 tests · 模拟运行'}</small></div>`:''}<h3>工作依据</h3><p>分支：demo/notification-fix<br>保留已有代码，不自动提交或推送。</p><h3>验证与交付</h3><p>${phase>=4?'自动化测试：通过（演示数据）<br>副驾驶审查：完成（演示数据）':'自动化测试：未完成<br>副驾驶审查：尚未完成'}<br>真实设备：${phase===5?'已由你确认（模拟）':'尚未验证'}</p><div class="actions">${button('查看示例改动','diff')}${button('交接给另一智能体','handoff')}</div>${mode==='team'&&!delegated?'<h3>可选协作</h3><p>不会默认启动工作会话。</p>'+button('查看委派方案','delegate'):''}`:'<p>开始一项任务后，这里显示负责人、验证结果与交接记录。</p>'}`;
 $('#bottom').textContent=`工程 imux　 ·　会话 ${count} 个（本次演示）${delegated?'，含 2 个工作会话':''}`;
 $('#controls').innerHTML=!task?button('关联当前会话','attach','primary'):closed?button('恢复负责的会话','restore','primary'):paused?button('继续这项任务','resume','primary'):phase===2?button('回答当前确认','answer','primary'):phase===4?button('确认设备验证通过','accept','primary')+button('设备验证失败','fail'):phase===5?button('查看交付结果','result','primary'):button('模拟下一步','next','primary')+button('模拟执行失败','error');
 if(task&&!closed&&!paused&&phase<5)$('#controls').innerHTML+=button('停止执行','stop');if(task&&!closed)$('#controls').innerHTML+=button('关闭会话','close');
 $('#message').disabled=closed;$('#chat button').disabled=closed;
}
function start(title,newSession=false,chosen='Codex'){
 if(newSession){history.push({agent,id:session});agent=chosen;session=++count;messages=[];}task=title;phase=0;paused=false;closed=false;
 add('你',title);add(agent,`我会在${newSession?'新建的':'当前'}会话 ${session} 中处理。先定位问题，再修改和验证。\n这是模拟执行，没有调用真实工具。`);notify(newSession?'已模拟新建会话；旧会话保留。':'已关联当前会话，没有新建会话。');render();
}
function act(action){
 if(action==='attach')start('修复手机重复通知，并在修复后审查');
 if(action==='focus'){details=true;render();}
 if(action==='details'){details=!details;render();}
 if(action==='next'){phase++;add(agent,phase===1?'已定位到重复转发路径。接下来修改并补充回归测试。（模拟）':phase===2?'需要确认：是否增加消息标识用于去重？这会影响转发接口。\n请选择继续或保持现有接口。':phase===4?'自动化测试和审查已结束，但真实手机尚未验证。\n本轮结束不等于任务验收通过。':'正在运行测试。（模拟）');render();}
 if(action==='answer')dialog('确认接口修改','<p>允许增加消息标识，同时更新调用方？</p><p>这里只模拟一次确认；实际插件应使用 CLI 自己的确认机制。</p><label for="decision">你的决定</label><select id="decision"><option value="yes">允许，继续修改和验证</option><option value="no">不允许，先调整方案</option></select>','提交决定',()=>{if($('#decision').value==='no'){paused=true;add('你','不修改接口，先调整方案。');add(agent,'已停止执行，保留任务和已有修改。你可以继续补充要求。');}else{phase=3;add('你','允许修改接口。');add(agent,'开始验证修改。（模拟）');}render();});
 if(action==='stop')dialog('停止当前执行？',`<p>${delegated?'主会话和两个工作会话都会停止。':'当前会话停止执行。'}任务记录与已有修改会保留，不会自动回滚。</p>`,'停止执行',()=>{paused=true;childrenStopped=delegated;add('imux','执行已停止（模拟）。任务与已有修改保留。');render();});
 if(action==='resume'){paused=false;childrenStopped=false;add(agent,'继续处理这项任务；先检查当前状态。（模拟）');render();}
 if(action==='close')dialog('关闭负责的会话？','<p>停止本任务当前执行；如有子任务，一并停止。保留任务与历史，不自动重开。手机发来普通消息时回复离线。</p>','关闭会话',()=>{closed=true;childrenStopped=delegated;add('imux','会话已关闭。任务保留，恢复需要你主动操作。');render();});
 if(action==='restore'){closed=false;paused=true;add('imux','已模拟恢复同一个会话。尚未自动继续执行，请点击“继续这项任务”。');render();}
 if(action==='handoff'){
 if(closed){notify('请先恢复会话，再发起交接。');return;}
 dialog('交接这项任务','<p>旧会话停止负责；新会话接收目标、改动位置、验证记录与未完成项。不会让两个会话同时修改。</p><label for="agent">接手的智能体</label><select id="agent">'+['Codex','Pi','Claude Code'].filter(a=>a!==agent).map(a=>`<option>${a}</option>`).join('')+'</select><p>新执行者需核对分支和文件。本原型仅展示该过程。</p>','确认交接',()=>{history.push({agent,id:session});agent=$('#agent').value;session=++count;paused=true;childrenStopped=delegated;add('imux',`已交接给 ${agent} · 会话 ${session}。旧会话及子任务停止执行，交接记录保留。`);add(agent,'已收到交接包。等待你继续，届时先核对当前工作区。（模拟）');render();});}
 if(action==='delegate')dialog('批准两个子任务？','<p>主会话仍由你当前的智能体负责。</p><p>Codex：后端修改，隔离目录 backend。<br>Pi：回归测试，隔离目录 tests。</p><p>将新增 2 个工作会话。最多并发 2 个，演示时限 20 分钟；不提交、不推送。整合后仍需整体测试。</p>','批准并创建（模拟）',()=>{delegated=true;count+=2;add('imux','已模拟创建 2 个隔离工作会话。主会话负责整合。停止任务会同时停止它们。');notify('仅演示授权与分工；没有启动进程或创建真实工作目录。');render();});
 if(action==='child')dialog('子任务的工作记录',`<p>这是已授权的独立工作会话，不是第二个总指挥。</p><p>状态：${childrenStopped?'已停止':phase>=4?'结果已返回主会话':'模拟执行中'}。</p><p>交付：修改清单、测试输出、未完成项。由主会话整合后重新验证。</p>`,'返回主会话',()=>true);
 if(action==='history')dialog('原会话的交接记录','<p>原会话已停止负责。目标、已有修改和未验证项已传给接手会话。历史保留，不删除 CLI 原始记录。</p>','知道了',()=>true);
 if(action==='diff')dialog('示例改动 · 非真实文件','<p>示例：转发消息前，按消息标识检查是否已经处理。</p><pre>+ if (alreadyForwarded(message.id)) return\n+ forward(message)\n+ markForwarded(message.id)</pre><p>这是用于体验的伪代码，不代表修复方案或实际工程改动。</p>','关闭',()=>true);
 if(action==='accept'){phase=5;add('你','设备验证通过（模拟确认）。');add(agent,'任务已验收。改动与验证记录可以查看，没有自动提交或推送。');render();}
 if(action==='fail'){phase=1;add('你','设备仍出现重复消息。');add(agent,'验收未通过，重新定位并修改。不会显示为完成。');render();}
 if(action==='error'){paused=true;childrenStopped=delegated;add('imux','模拟执行失败：测试进程未能启动。任务没有完成。可补充环境信息后重试。');notify('失败状态可恢复：点击“继续这项任务”。');render();}
 if(action==='result')dialog('交付结果 · 全部为模拟','<p>修改：已完成<br>自动化测试：通过<br>审查：已完成<br>设备验证：你已确认</p><p>未提交、未推送。真实实现必须附测试命令和输出；本原型不提供虚假的执行证据。</p>','关闭',()=>true);
}
document.addEventListener('click',e=>{const b=e.target.closest('[data-action]');if(b)act(b.dataset.action);});
$('#tasks').onclick=()=>{view='tasks';render();};$('#sessions').onclick=()=>{view='sessions';render();};$('#reset').onclick=()=>location.reload();
$('#create').onclick=()=>{if(task){notify('这一版每次体验一项任务。请点“重新体验”开始另一项；不会覆盖正在进行的任务。');return;}dialog('从任务入口开始','<label for="title">你想完成什么？</label><input id="title" value="修复手机重复通知，并在修复后审查" maxlength="120"><label for="newagent">执行者</label><select id="newagent"><option>Codex</option><option>Pi</option><option>Claude Code</option></select><p>创建新的执行会话；不会把任务塞进当前讨论。仅模拟，不运行 CLI。</p>','新建并开始',()=>{const title=$('#title').value.trim();if(!title){$('#title').focus();return false;}start(title,true,$('#newagent').value);details=true;render();});};
$('#chat').onsubmit=e=>{e.preventDefault();const input=$('#message');if(!input.value.trim())return;add('你',input.value.trim());add('演示说明','已记录这条输入。这里没有连接模型，不会自动理解、执行或创建任务；请使用演示控制台继续体验。');input.value='';render();};
add('你','我想修复手机重复通知，但先不要另外开会话。');add(agent,'我们可以继续在这里讨论。需要开始时，把这件事关联为任务即可；不会自动委派给其他智能体。');render();
