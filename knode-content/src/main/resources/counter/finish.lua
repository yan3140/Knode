local max64='9223372036854775807'
local function number64(s,positive)
  if type(s)~='string' or not string.match(s,'^%d+$') then return false end
  if #s>1 and string.sub(s,1,1)=='0' then return false end
  if positive and s=='0' then return false end
  return #s<#max64 or (#s==#max64 and s<=max64)
end
local function typ(k) return redis.call('TYPE',k).ok end
if #KEYS~=2 or #ARGV~=3 or KEYS[1]==KEYS[2] or not number64(ARGV[1],true)
  or not number64(ARGV[2],false) or not string.match(ARGV[3],'^%d+$') or #ARGV[3]>15 then return 'ERR' end
local c,d=typ(KEYS[1]),typ(KEYS[2])
if (c~='none' and c~='hash') or (d~='none' and d~='zset') then return 'ERR' end
local score=redis.call('ZSCORE',KEYS[2],ARGV[1])
if not score or score~=ARGV[3] then return 'STALE' end
local current=redis.call('HGET',KEYS[1],ARGV[1])
if not number64(current,false) or type(redis.acl_check_cmd)~='function' then return 'ERR' end
if current==ARGV[2] then
  if not redis.acl_check_cmd('ZREM',KEYS[2],ARGV[1]) then return 'ERR' end
  redis.call('ZREM',KEYS[2],ARGV[1]); return 'DONE'
end
local tm=redis.call('TIME')
local now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000)
if now<0 or now>9007199254710000 or not redis.acl_check_cmd('ZADD',KEYS[2],now,ARGV[1]) then return 'ERR' end
redis.call('ZADD',KEYS[2],now,ARGV[1])
return 'AGAIN'
