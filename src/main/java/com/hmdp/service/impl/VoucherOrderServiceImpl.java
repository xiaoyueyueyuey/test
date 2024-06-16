package com.hmdp.service.impl;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);//阻塞队列
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.execute(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    VoucherOrder voucherOrder = orderTasks.take();//获取队列中的订单
                    handleVoucherOrder(voucherOrder);//保存订单
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public IVoucherOrderService proxy ;
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        //3.获取锁
        boolean isLock = lock.tryLock();
        //4.判断是否获取锁成功
        if(!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            //获取代理对象
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }
    //优化版秒杀
    @Override
    public Result seckillVoucher(Long voucherId) {

        //1.执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString()
        );
        int r = result.intValue();
        if(r != 0){ //2.判断结果是否为0,不为0,代表没有购买资格
            return Result.fail(r==1 ? "库存不足":"不能重复下单");
        }
        //2.2.为0,有购买资格,把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        //封装
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);//订单id
        voucherOrder.setUserId(UserHolder.getUser().getId());//用户id
        voucherOrder.setVoucherId(voucherId);//代金券id
        //保存阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3.返回订单id
        return Result.ok(orderId);
    }
//
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        //判断秒杀是否开始
//        if ( voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀未开始");
//        }
//        //判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已结束");
//        }
//        //判断库存是否充足
//        if (voucher.getStock() <= 0) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            return Result.fail("请勿重复提交");
//        }
//
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();//获取代理对象，确保事务生效
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//    }

//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        //一人一单
//        Long userId = UserHolder.getUser().getId();
//
//        Long count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
//        if (count > 0) {
//            return Result.fail("每人限购一张");
//        }
//        //扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1")
////                .eq("voucher_id", voucherId).eq("stock", voucher.getStock())//使用乐观锁解决超卖问题
//                .eq("voucher_id", voucherId).gt("stock", 0)//使用乐观锁解决超卖问题
//                .update();
//        if (!success) {
//            return Result.fail("库存不足");
//        }
//        //创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//
////        用户id
//        voucherOrder.setUserId(UserHolder.getUser().getId());
////      代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //返回订单Id
//        save(voucherOrder);
//
//        return Result.ok(orderId);
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //6.一人一单
        Long userId = voucherOrder.getUserId();
        //6.1查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //6.2判断是否存在
        if(count>0){
            //用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }
        //3.2库存充足扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") //相当于set条件 set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()) //相当于where条件 where id = ? and stock = ?
                .gt("stock",0).update();
        if(!success){
            log.error("库存不足!");
            return;
        }

        long orderId = redisIdWorker.nextId("order");//订单id
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherOrder.getVoucherId());//代金券id
        save(voucherOrder);

    }

}
