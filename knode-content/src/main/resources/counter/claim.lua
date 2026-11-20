local max64='9223372036854775807'
local function number64(s,positive)
  if type(s)~='string' or not string.match(s,'^%d+$') then return false end
  if #s>1 and string.sub(s,1,1)=='0' then return false end
  if positive and s=='0' then return false end
  return #s<#max64 or (#s==#max64 and s<=max64)
end
local function typ(k) return redis.call('TYPE',k).ok end
if #KEYS~=2 or #ARGV~=1 or not string.match(ARGV[1],'^[1-9]%d*$')
  or #ARGV[1]>3 or tonumber(ARGV[1])>100 or KEYS[1]==KEYS[2] then return 'ERR' end
local c,d=typ(KEYS[1]),typ(KEYS[2])
if (c~='none' and c~='hash') or (d~='none' and d~='zset') then return 'ERR' end
local tm=redis.call('TIME')
local now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000)
if now<0 or now>9007199254710000 or type(redis.acl_check_cmd)~='function' then return 'ERR' end
local ids=redis.call('ZRANGEBYSCORE',KEYS[2],'-inf',now,'LIMIT',0,tonumber(ARGV[1]))
local values={}
for i,id in ipairs(ids) do
  local value=redis.call('HGET',KEYS[1],id)
  values[i]=value
  if not redis.acl_check_cmd('ZADD',KEYS[2],now+30000,id) then return 'ERR' end
end
-- Claims are leases: a crash or unexpected command error leaves each member retryable.
local out={}
local bad=0
for i,id in ipairs(ids) do
  redis.call('ZADD',KEYS[2],now+30000,id)
  if number64(id,true) and number64(values[i],false) then
    table.insert(out,id..','..values[i]..','..redis.call('ZSCORE',KEYS[2],id))
  else bad=bad+1 end
end
return tostring(bad)..';'..table.concat(out,';')
