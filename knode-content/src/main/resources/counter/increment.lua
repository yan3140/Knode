-- Counts and IDs stay canonical decimal strings; never convert a count to Lua's double.
local max64 = '9223372036854775807'
local function number64(s, positive)
  if type(s) ~= 'string' or not string.match(s, '^%d+$') then return false end
  if #s > 1 and string.sub(s,1,1) == '0' then return false end
  if positive and s == '0' then return false end
  return #s < #max64 or (#s == #max64 and s <= max64)
end
local function greater(a,b)
  return #a > #b or (#a == #b and a > b)
end
local function nextcount(s)
  local result = {}
  local carry = 1
  for i = #s,1,-1 do
    local d = string.byte(s,i)-48+carry
    if d >= 10 then d=d-10; carry=1 else carry=0 end
    result[i] = string.char(d+48)
  end
  if carry == 1 then table.insert(result,1,'1') end
  return table.concat(result)
end
local function typ(k) return redis.call('TYPE',k).ok end
if #KEYS ~= 5 or #ARGV ~= 3 or not number64(ARGV[1],true)
    or not number64(ARGV[2],false) or (ARGV[3] ~= '0' and ARGV[3] ~= '1') then return 'ERR' end
for i=1,5 do for j=i+1,5 do if KEYS[i] == KEYS[j] then return 'ERR' end end end
local expected = {'hash','zset','string','string','string'}
for i=1,5 do
  local t=typ(KEYS[i])
  if t ~= 'none' and t ~= expected[i] then return 'ERR' end
end
local rates,ttls={},{}
local limits={300,20}
for i=1,2 do
  local v=redis.call('GET',KEYS[i+2])
  if v then
    local ttl=redis.call('PTTL',KEYS[i+2])
    if not string.match(v,'^[1-9]%d*$') or #v>3 or tonumber(v)>limits[i] or ttl<0 then return 'ERR' end
    rates[i]=tonumber(v); ttls[i]=ttl
  else rates[i]=0; ttls[i]=0 end
end
local duplicate=redis.call('GET',KEYS[5])
if duplicate and (duplicate~='1' or redis.call('PTTL',KEYS[5])<0) then return 'ERR' end
local current=redis.call('HGET',KEYS[1],ARGV[1])
local dirty=redis.call('ZSCORE',KEYS[2],ARGV[1])
if current and not number64(current,false) then return 'ERR' end
if not current and dirty then return 'ERR' end
if current and greater(ARGV[2],current) and dirty then return 'ERR' end
local effective=current or ARGV[2]
if greater(ARGV[2],effective) then effective=ARGV[2] end
local retry=0
for i=1,2 do
  if rates[i]>=limits[i] then retry=math.max(retry,math.max(1,math.ceil(ttls[i]/1000))) end
end
if retry>0 then return 'L:'..retry end
if ARGV[3]=='1' and not duplicate and effective==max64 then return 'ERR' end
-- Redis 7 ACL checks are performed before any write. Scripts do not roll back errors.
if type(redis.acl_check_cmd)~='function' then return 'ERR' end
local now=0
if ARGV[3]=='1' and not duplicate then
  local tm=redis.call('TIME')
  now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000)
  if now<0 or now>9007199254710000 then return 'ERR' end
  if not redis.acl_check_cmd('ZADD',KEYS[2],'NX',now,ARGV[1])
    or not redis.acl_check_cmd('HSET',KEYS[1],ARGV[1],nextcount(effective))
    or not redis.acl_check_cmd('SET',KEYS[5],'1','PX',1800000) then return 'ERR' end
elseif ARGV[3]=='1' and (not current or greater(effective,current)) then
  if not redis.acl_check_cmd('HSET',KEYS[1],ARGV[1],effective) then return 'ERR' end
end
for i=1,2 do
  if rates[i]==0 then
    if not redis.acl_check_cmd('SET',KEYS[i+2],'1','PX',60000) then return 'ERR' end
  elseif not redis.acl_check_cmd('INCR',KEYS[i+2]) then return 'ERR' end
end
if ARGV[3]=='1' then
  if duplicate then
    if not current or greater(effective,current) then redis.call('HSET',KEYS[1],ARGV[1],effective) end
  else
    redis.call('ZADD',KEYS[2],'NX',now,ARGV[1])
    redis.call('HSET',KEYS[1],ARGV[1],nextcount(effective))
    redis.call('SET',KEYS[5],'1','PX',1800000)
  end
end
for i=1,2 do
  if rates[i]==0 then redis.call('SET',KEYS[i+2],'1','PX',60000)
  else redis.call('INCR',KEYS[i+2]) end
end
if ARGV[3]=='0' then return 'HIDDEN' end
if duplicate then return 'DUP' end
return 'OK'
