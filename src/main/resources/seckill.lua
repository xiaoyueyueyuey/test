-- 优惠券id
local voucherId= ARGV[1]
-- 用户id
local userId= ARGV[2]

-- 数据key


--库存key
local stockKey='seckill:stock:'..voucherId

--订单key
local orderKey='seckill:order:'..voucherId


--脚本业务
--判断库存是否充足 get stockKey
if(tonumber(redis.call('get',stockKey))<=0)
    then
    return 1

end

if(redis.call('sismember',orderKey,userId)==1)
    then
    --已经抢购过
    return 2
end
--减库存
redis.call('incrby',stockKey,-1)
--下单（保存用户） sadd orderKey userId
redis.call('sadd',orderKey,userId)