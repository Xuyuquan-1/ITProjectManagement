```java
package com.library.reservation;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 图书管理系统预约功能模块
 * 包含：预约图书、取消预约、查询预约、取书、自动过期处理
 */
public class BookReservationService {

    // ========== 数据存储（模拟数据库） ==========
    private final Map<Long, Book> bookDB = new ConcurrentHashMap<>();
    private final Map<Long, User> userDB = new ConcurrentHashMap<>();
    private final Map<Long, Reservation> reservationDB = new ConcurrentHashMap<>();
    private final AtomicLong reservationIdGenerator = new AtomicLong(1);
    private final AtomicLong bookIdGenerator = new AtomicLong(1);
    private final AtomicLong userIdGenerator = new AtomicLong(1);

    // 预约状态常量
    private static final int STATUS_WAITING = 0;      // 等待中（排队）
    private static final int STATUS_ACTIVE = 1;       // 已生效/待取书
    private static final int STATUS_CANCELLED = 2;    // 已取消
    private static final int STATUS_EXPIRED = 3;      // 已过期
    private static final int STATUS_PICKED = 4;       // 已取书

    // 预约保留时长（小时）
    private static final int RESERVE_HOURS = 48;

    // ========== 实体类 ==========
    static class Book {
        Long id; String isbn; String title; String author;
        int totalCopies;      // 总馆藏量
        int availableCopies;  // 可借数量
        int reservedCount;    // 当前预约人数（含排队）
        int status;           // 0:正常 1:下架
        
        Book(String isbn, String title, String author, int totalCopies) {
            this.id = bookIdGenerator.getAndIncrement();
            this.isbn = isbn; this.title = title; this.author = author;
            this.totalCopies = totalCopies;
            this.availableCopies = totalCopies;
            this.reservedCount = 0;
            this.status = 0;
        }
    }

    static class User {
        Long id; String name; String phone; boolean banned;
        User(String name, String phone) {
            this.id = userIdGenerator.getAndIncrement();
            this.name = name; this.phone = phone; this.banned = false;
        }
    }

    static class Reservation {
        Long id; Long userId; Long bookId; String reservationNo;
        int queuePosition; int status;
        LocalDateTime reserveTime; LocalDateTime validUntil;
        LocalDateTime cancelTime; LocalDateTime pickTime;
        
        Reservation(Long userId, Long bookId, int queuePosition) {
            this.id = reservationIdGenerator.getAndIncrement();
            this.userId = userId; this.bookId = bookId;
            this.reservationNo = "RES" + System.currentTimeMillis() + userId;
            this.queuePosition = queuePosition;
            this.status = STATUS_WAITING;
            this.reserveTime = LocalDateTime.now();
            this.validUntil = this.reserveTime.plusHours(RESERVE_HOURS);
        }
    }

    // ========== DTO ==========
    static class ReserveRequest {
        Long userId; Long bookId;
        ReserveRequest(Long userId, Long bookId) { this.userId = userId; this.bookId = bookId; }
    }

    static class ReserveResult {
        boolean success; String message; Long reservationId; String reservationNo; int queuePosition;
        public static ReserveResult success(String msg, Long id, String no, int pos) {
            ReserveResult r = new ReserveResult(); r.success = true; r.message = msg;
            r.reservationId = id; r.reservationNo = no; r.queuePosition = pos; return r;
        }
        public static ReserveResult fail(String msg) {
            ReserveResult r = new ReserveResult(); r.success = false; r.message = msg; return r;
        }
    }

    static class ReservationVO {
        Long id; String reservationNo; String bookTitle; String userName;
        int status; String statusDesc; LocalDateTime reserveTime; LocalDateTime validUntil; int queuePosition;
    }

    // ========== 核心业务方法 ==========
    
    /**
     * 预约图书
     */
    public synchronized ReserveResult reserveBook(ReserveRequest request) {
        // 1. 校验用户
        User user = userDB.get(request.userId);
        if (user == null) return ReserveResult.fail("用户不存在");
        if (user.banned) return ReserveResult.fail("用户已被封禁");
        
        // 2. 校验图书
        Book book = bookDB.get(request.bookId);
        if (book == null) return ReserveResult.fail("图书不存在");
        if (book.status != 0) return ReserveResult.fail("图书已下架");
        
        // 3. 检查是否已预约未取书
        boolean alreadyReserved = reservationDB.values().stream()
                .anyMatch(r -> r.userId.equals(request.userId) && r.bookId.equals(request.bookId)
                        && (r.status == STATUS_WAITING || r.status == STATUS_ACTIVE));
        if (alreadyReserved) return ReserveResult.fail("您已预约过该书，请先处理现有预约");
        
        // 4. 计算排队位置
        long waitingCount = reservationDB.values().stream()
                .filter(r -> r.bookId.equals(request.bookId) && r.status == STATUS_WAITING)
                .count();
        int queuePos = (int) waitingCount + 1;
        
        // 5. 判断是否有可借副本
        boolean hasAvailable = book.availableCopies > 0;
        int initialStatus = hasAvailable ? STATUS_ACTIVE : STATUS_WAITING;
        
        // 6. 创建预约记录
        Reservation reservation = new Reservation(request.userId, request.bookId, queuePos);
        reservation.status = initialStatus;
        if (hasAvailable) {
            reservation.validUntil = LocalDateTime.now().plusHours(RESERVE_HOURS);
        }
        reservationDB.put(reservation.id, reservation);
        
        // 7. 更新图书数据
        book.reservedCount++;
        if (hasAvailable) {
            book.availableCopies--;
        }
        
        String msg = hasAvailable ? "预约成功，请在48小时内到馆取书" : "预约成功，当前排队第" + queuePos + "位";
        return ReserveResult.success(msg, reservation.id, reservation.reservationNo, queuePos);
    }
    
    /**
     * 取消预约
     */
    public synchronized boolean cancelReservation(Long reservationId, Long userId) {
        Reservation reservation = reservationDB.get(reservationId);
        if (reservation == null) return false;
        if (!reservation.userId.equals(userId)) return false;
        if (reservation.status == STATUS_CANCELLED || reservation.status == STATUS_EXPIRED) return false;
        
        // 记录取消时间
        reservation.status = STATUS_CANCELLED;
        reservation.cancelTime = LocalDateTime.now();
        
        // 更新图书数据
        Book book = bookDB.get(reservation.bookId);
        if (book != null) {
            book.reservedCount--;
            if (reservation.status == STATUS_ACTIVE) {
                book.availableCopies++;
            }
        }
        
        // 重新排队：将后续等待者的排队位置前移
        reorderQueue(reservation.bookId);
        return true;
    }
    
    /**
     * 重新计算排队顺序（当有人取消或取书后）
     */
    private void reorderQueue(Long bookId) {
        List<Reservation> waitingList = reservationDB.values().stream()
                .filter(r -> r.bookId.equals(bookId) && r.status == STATUS_WAITING)
                .sorted(Comparator.comparing(Reservation::getReserveTime))
                .collect(Collectors.toList());
        
        int pos = 1;
        for (Reservation r : waitingList) {
            r.queuePosition = pos++;
        }
    }
    
    /**
     * 取书（用户到馆取书）
     */
    public synchronized boolean pickBook(String reservationNo, Long userId) {
        Optional<Reservation> opt = reservationDB.values().stream()
                .filter(r -> r.reservationNo.equals(reservationNo) && r.userId.equals(userId))
                .findFirst();
        
        if (!opt.isPresent()) return false;
        Reservation reservation = opt.get();
        
        if (reservation.status != STATUS_ACTIVE) {
            return false;
        }
        
        // 更新预约状态
        reservation.status = STATUS_PICKED;
        reservation.pickTime = LocalDateTime.now();
        
        // 更新图书数据（保留数量减少，预约已被消耗，不再额外增加availableCopies）
        Book book = bookDB.get(reservation.bookId);
        if (book != null) {
            book.reservedCount--;
        }
        
        // 将下一个等待者激活
        activateNextWaiter(reservation.bookId);
        return true;
    }
    
    /**
     * 激活下一个等待者（当有可借副本空出时）
     */
    private void activateNextWaiter(Long bookId) {
        Reservation nextWaiter = reservationDB.values().stream()
                .filter(r -> r.bookId.equals(bookId) && r.status == STATUS_WAITING)
                .min(Comparator.comparing(Reservation::getReserveTime))
                .orElse(null);
        
        if (nextWaiter != null) {
            Book book = bookDB.get(bookId);
            if (book != null && book.availableCopies > 0) {
                nextWaiter.status = STATUS_ACTIVE;
                nextWaiter.validUntil = LocalDateTime.now().plusHours(RESERVE_HOURS);
                book.availableCopies--;
            }
        }
    }
    
    /**
     * 自动处理过期预约（定时任务调用）
     */
    public synchronized int autoExpireReservations() {
        List<Reservation> activeList = reservationDB.values().stream()
                .filter(r -> r.status == STATUS_ACTIVE && LocalDateTime.now().isAfter(r.validUntil))
                .collect(Collectors.toList());
        
        for (Reservation r : activeList) {
            r.status = STATUS_EXPIRED;
            Book book = bookDB.get(r.bookId);
            if (book != null) {
                book.reservedCount--;
                book.availableCopies++;
                activateNextWaiter(r.bookId);
            }
        }
        return activeList.size();
    }
    
    /**
     * 查询用户预约列表
     */
    public List<ReservationVO> getUserReservations(Long userId, Integer status) {
        return reservationDB.values().stream()
                .filter(r -> r.userId.equals(userId))
                .filter(r -> status == null || r.status == status)
                .map(r -> {
                    ReservationVO vo = new ReservationVO();
                    vo.id = r.id;
                    vo.reservationNo = r.reservationNo;
                    vo.status = r.status;
                    vo.statusDesc = getStatusDesc(r.status);
                    vo.reserveTime = r.reserveTime;
                    vo.validUntil = r.validUntil;
                    vo.queuePosition = r.queuePosition;
                    
                    Book b = bookDB.get(r.bookId);
                    if (b != null) vo.bookTitle = b.title;
                    User u = userDB.get(r.userId);
                    if (u != null) vo.userName = u.name;
                    return vo;
                })
                .sorted(Comparator.comparing((ReservationVO v) -> v.reserveTime).reversed())
                .collect(Collectors.toList());
    }
    
    private String getStatusDesc(int status) {
        switch (status) {
            case STATUS_WAITING: return "排队中";
            case STATUS_ACTIVE: return "待取书";
            case STATUS_CANCELLED: return "已取消";
            case STATUS_EXPIRED: return "已过期";
            case STATUS_PICKED: return "已取书";
            default: return "未知";
        }
    }
    
    // ========== 初始化测试数据 ==========
    public void initTestData() {
        Book book1 = new Book("9787121341568", "Java编程思想", "Bruce Eckel", 3);
        Book book2 = new Book("9787115533173", "Spring实战", "Craig Walls", 2);
        bookDB.put(book1.id, book1);
        bookDB.put(book2.id, book2);
        
        User user1 = new User("张三", "13800001111");
        User user2 = new User("李四", "13800002222");
        userDB.put(user1.id, user1);
        userDB.put(user2.id, user2);
    }
    
    // 辅助方法（供外部调用）
    public Map<Long, Book> getBookDB() { return bookDB; }
    public Map<Long, User> getUserDB() { return userDB; }
    public Map<Long, Reservation> getReservationDB() { return reservationDB; }
}
```