package com.spectator.plugin;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.util.Vector;

import java.util.*;

public class SpectatorPlugin extends JavaPlugin implements Listener {
    
    private Map<UUID, UUID> spectatorTargets = new HashMap<>();
    private Map<UUID, Boolean> autoRotate = new HashMap<>();
    private Map<UUID, Integer> rotateInterval = new HashMap<>();
    private Map<UUID, BukkitRunnable> rotateTasks = new HashMap<>();
    private Set<UUID> spectatorMode = new HashSet<>();
    
    // เก็บ offset ของกล้องแต่ละคน (ระยะห่างจาก target)
    private Map<UUID, Vector> cameraOffsets = new HashMap<>();
    
    // การตั้งค่ากล้อง
    private final double DEFAULT_DISTANCE = 5.0;
    private final double DEFAULT_HEIGHT = 2.0;
    private final double MIN_DISTANCE = 2.0;
    private final double MAX_DISTANCE = 20.0;
    private final double SMOOTH_FACTOR = 0.3; // ค่าความนุ่มนวลในการเคลื่อนที่
    
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        
        // เริ่มต้น task สำหรับติดตามผู้เล่น
        new BukkitRunnable() {
            @Override
            public void run() {
                updateSpectatorPositions();
            }
        }.runTaskTimer(this, 0L, 1L); // อัพเดตทุก tick สำหรับความลื่นไหล
        
        getLogger().info("Spectator Plugin เปิดใช้งานแล้ว!");
    }
    
    @Override
    public void onDisable() {
        // ยกเลิก tasks ทั้งหมด
        for (BukkitRunnable task : rotateTasks.values()) {
            task.cancel();
        }
        getLogger().info("Spectator Plugin ปิดใช้งานแล้ว!");
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cคำสั่งนี้ใช้ได้เฉพาะผู้เล่นเท่านั้น!");
            return true;
        }
        
        Player player = (Player) sender;
        
        if (command.getName().equalsIgnoreCase("spectate")) {
            if (args.length == 0) {
                player.sendMessage("§e=== คำสั่ง Spectator ===");
                player.sendMessage("§a/spectate <ชื่อผู้เล่น> §7- ติดตามผู้เล่นที่ระบุ");
                player.sendMessage("§a/spectate random §7- สุ่มติดตามผู้เล่น");
                player.sendMessage("§a/spectate auto <นาที> §7- เปิดโหมดสุ่มอัตโนมัติ");
                player.sendMessage("§a/spectate stop §7- หยุดติดตาม");
                player.sendMessage("§a/spectate list §7- แสดงรายชื่อผู้เล่น");
                player.sendMessage("§7");
                player.sendMessage("§7ใช้ WASD เพื่อขยับกล้องได้อิสระ");
                player.sendMessage("§7Space/Shift เพื่อเลื่อนขึ้น/ลง");
                return true;
            }
            
            if (args[0].equalsIgnoreCase("stop")) {
                stopSpectating(player);
                player.sendMessage("§aหยุดการติดตามแล้ว!");
                return true;
            }
            
            if (args[0].equalsIgnoreCase("list")) {
                player.sendMessage("§e=== ผู้เล่นออนไลน์ ===");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!spectatorMode.contains(p.getUniqueId())) {
                        String world = p.getWorld().getName();
                        player.sendMessage("§7- §a" + p.getName() + " §8[" + world + "]");
                    }
                }
                return true;
            }
            
            if (args[0].equalsIgnoreCase("random")) {
                Player target = getRandomPlayer(player);
                if (target == null) {
                    player.sendMessage("§cไม่มีผู้เล่นให้ติดตาม!");
                    return true;
                }
                startSpectating(player, target);
                player.sendMessage("§aกำลังติดตาม: §e" + target.getName() + " §8[" + target.getWorld().getName() + "]");
                return true;
            }
            
            if (args[0].equalsIgnoreCase("auto")) {
                if (args.length < 2) {
                    player.sendMessage("§cใช้คำสั่ง: /spectate auto <นาที>");
                    return true;
                }
                
                try {
                    int minutes = Integer.parseInt(args[1]);
                    if (minutes < 1) {
                        player.sendMessage("§cระบุเวลาอย่างน้อย 1 นาที!");
                        return true;
                    }
                    
                    startAutoRotate(player, minutes);
                    player.sendMessage("§aเปิดโหมดสุ่มอัตโนมัติ: เปลี่ยนทุก §e" + minutes + " §aนาที");
                    return true;
                    
                } catch (NumberFormatException e) {
                    player.sendMessage("§cกรุณาระบุตัวเลขที่ถูกต้อง!");
                    return true;
                }
            }
            
            // ติดตามผู้เล่นที่ระบุ
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                player.sendMessage("§cไม่พบผู้เล่น: " + args[0]);
                return true;
            }
            
            if (target.equals(player)) {
                player.sendMessage("§cคุณไม่สามารถติดตามตัวเองได้!");
                return true;
            }
            
            if (spectatorMode.contains(target.getUniqueId())) {
                player.sendMessage("§cไม่สามารถติดตามผู้เล่นที่อยู่ในโหมด Spectator ได้!");
                return true;
            }
            
            startSpectating(player, target);
            player.sendMessage("§aกำลังติดตาม: §e" + target.getName() + " §8[" + target.getWorld().getName() + "]");
            return true;
        }
        
        return false;
    }
    
    private void startSpectating(Player spectator, Player target) {
        // หยุด auto-rotate ถ้ามี
        stopAutoRotate(spectator);
        
        UUID spectatorUUID = spectator.getUniqueId();
        
        // บันทึกสถานะ
        spectatorMode.add(spectatorUUID);
        spectatorTargets.put(spectatorUUID, target.getUniqueId());
        
        // รีเซ็ต camera offset เป็นค่าเริ่มต้น
        cameraOffsets.put(spectatorUUID, new Vector(0, DEFAULT_HEIGHT, -DEFAULT_DISTANCE));
        
        // ตั้งค่าผู้เล่นเป็นโหมด Spectator
        spectator.setGameMode(GameMode.SPECTATOR);
        spectator.setAllowFlight(true);
        spectator.setFlying(true);
        
        // ซ่อนตัวจากผู้เล่นอื่น
        spectator.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, false, false));
        
        // วาร์ปไปยังโลกเดียวกับ target ถ้าต่างโลก
        if (!spectator.getWorld().equals(target.getWorld())) {
            spectator.teleport(target.getLocation());
        }
        
        // วาร์ปไปที่ target
        updateCameraPosition(spectator, target, true);
    }
    
    private void stopSpectating(Player spectator) {
        UUID uuid = spectator.getUniqueId();
        
        spectatorMode.remove(uuid);
        spectatorTargets.remove(uuid);
        cameraOffsets.remove(uuid);
        stopAutoRotate(spectator);
        
        spectator.setGameMode(GameMode.SURVIVAL);
        spectator.removePotionEffect(PotionEffectType.INVISIBILITY);
        spectator.setAllowFlight(false);
        spectator.setFlying(false);
    }
    
    private void startAutoRotate(Player spectator, int minutes) {
        stopAutoRotate(spectator);
        
        autoRotate.put(spectator.getUniqueId(), true);
        rotateInterval.put(spectator.getUniqueId(), minutes);
        
        // สุ่มผู้เล่นแรก
        Player target = getRandomPlayer(spectator);
        if (target != null) {
            startSpectating(spectator, target);
        }
        
        // สร้าง task สำหรับสุ่มเปลี่ยน
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!spectator.isOnline() || !autoRotate.getOrDefault(spectator.getUniqueId(), false)) {
                    cancel();
                    return;
                }
                
                Player newTarget = getRandomPlayer(spectator);
                if (newTarget != null) {
                    startSpectating(spectator, newTarget);
                    spectator.sendMessage("§e[Auto] §aเปลี่ยนไปติดตาม: §e" + newTarget.getName());
                }
            }
        };
        
        long ticks = minutes * 60 * 20L; // แปลงนาทีเป็น ticks
        task.runTaskTimer(this, ticks, ticks);
        rotateTasks.put(spectator.getUniqueId(), task);
    }
    
    private void stopAutoRotate(Player spectator) {
        UUID uuid = spectator.getUniqueId();
        autoRotate.remove(uuid);
        rotateInterval.remove(uuid);
        
        BukkitRunnable task = rotateTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }
    
    private Player getRandomPlayer(Player exclude) {
        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(exclude) && !spectatorMode.contains(p.getUniqueId())) {
                players.add(p);
            }
        }
        
        if (players.isEmpty()) {
            return null;
        }
        
        return players.get(new Random().nextInt(players.size()));
    }
    
    private void updateSpectatorPositions() {
        for (Map.Entry<UUID, UUID> entry : new HashMap<>(spectatorTargets).entrySet()) {
            Player spectator = Bukkit.getPlayer(entry.getKey());
            Player target = Bukkit.getPlayer(entry.getValue());
            
            if (spectator == null || target == null || !spectator.isOnline() || !target.isOnline()) {
                continue;
            }
            
            // ตรวจสอบว่าอยู่คนละโลกหรือไม่
            if (!spectator.getWorld().equals(target.getWorld())) {
                spectator.teleport(target.getLocation());
                spectator.sendMessage("§e" + target.getName() + " §7ย้ายไปโลก: §e" + target.getWorld().getName());
                cameraOffsets.put(spectator.getUniqueId(), new Vector(0, DEFAULT_HEIGHT, -DEFAULT_DISTANCE));
                continue;
            }
            
            // อัพเดตตำแหน่งกล้อง
            updateCameraPosition(spectator, target, false);
        }
    }
    
    private void updateCameraPosition(Player spectator, Player target, boolean instant) {
        UUID spectatorUUID = spectator.getUniqueId();
        Location targetLoc = target.getLocation();
        Location spectatorLoc = spectator.getLocation();
        
        // ดึง camera offset ปัจจุบัน
        Vector offset = cameraOffsets.getOrDefault(spectatorUUID, new Vector(0, DEFAULT_HEIGHT, -DEFAULT_DISTANCE));
        
        // คำนวณตำแหน่งเป้าหมายของกล้องตาม offset
        double yaw = Math.toRadians(targetLoc.getYaw());
        double pitch = Math.toRadians(targetLoc.getPitch());
        
        // หมุน offset ตามทิศทางของ target
        Vector rotatedOffset = rotateVector(offset, yaw, pitch);
        
        // คำนวณตำแหน่งเป้าหมาย
        Location targetCameraLoc = targetLoc.clone().add(rotatedOffset);
        
        // ถ้าไม่ใช่การวาร์ปแบบทันที ให้ใช้ smooth movement
        if (!instant && spectatorLoc.getWorld().equals(targetCameraLoc.getWorld())) {
            // คำนวณความแตกต่างระหว่างตำแหน่งปัจจุบันและเป้าหมาย
            Vector diff = targetCameraLoc.toVector().subtract(spectatorLoc.toVector());
            
            // ให้ผู้เล่นสามารถขยับกล้องได้อิสระ
            // ถ้าขยับมากเกินไป ค่อยๆ ดึงกลับมา
            double distance = diff.length();
            
            if (distance > MAX_DISTANCE) {
                // ถ้าห่างเกินไป บังคับดึงกลับมา
                Location newLoc = spectatorLoc.clone().add(diff.multiply(SMOOTH_FACTOR));
                spectator.teleport(newLoc);
            } else if (distance > 1.0) {
                // ค่อยๆ ดึงกลับมาอย่างนุ่มนวล
                Location newLoc = spectatorLoc.clone().add(diff.multiply(SMOOTH_FACTOR * 0.5));
                spectator.teleport(newLoc);
            }
            
            // อัพเดต offset ตามตำแหน่งที่ผู้เล่นขยับไป
            Vector actualOffset = spectatorLoc.toVector().subtract(targetLoc.toVector());
            Vector localOffset = rotateVectorInverse(actualOffset, yaw, pitch);
            
            // จำกัดระยะ
            double dist = localOffset.length();
            if (dist > MAX_DISTANCE) {
                localOffset.normalize().multiply(MAX_DISTANCE);
            } else if (dist < MIN_DISTANCE) {
                localOffset.normalize().multiply(MIN_DISTANCE);
            }
            
            cameraOffsets.put(spectatorUUID, localOffset);
            
        } else {
            // วาร์ปแบบทันที
            spectator.teleport(targetCameraLoc);
        }
        
        // ให้กล้องหันไปทาง target เสมอ
        Vector direction = targetLoc.toVector().subtract(spectator.getLocation().toVector());
        Location lookAt = spectator.getLocation().setDirection(direction);
        spectator.teleport(lookAt);
    }
    
    private Vector rotateVector(Vector vec, double yaw, double pitch) {
        // หมุนรอบแกน Y (yaw)
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        double x = vec.getX() * cos - vec.getZ() * sin;
        double z = vec.getX() * sin + vec.getZ() * cos;
        
        return new Vector(x, vec.getY(), z);
    }
    
    private Vector rotateVectorInverse(Vector vec, double yaw, double pitch) {
        // หมุนกลับรอบแกน Y
        double cos = Math.cos(-yaw);
        double sin = Math.sin(-yaw);
        double x = vec.getX() * cos - vec.getZ() * sin;
        double z = vec.getX() * sin + vec.getZ() * cos;
        
        return new Vector(x, vec.getY(), z);
    }
    
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player died = event.getEntity();
        
        // ตรวจสอบว่ามี spectator ติดตามผู้เล่นที่ตายหรือไม่
        for (Map.Entry<UUID, UUID> entry : spectatorTargets.entrySet()) {
            if (entry.getValue().equals(died.getUniqueId())) {
                Player spectator = Bukkit.getPlayer(entry.getKey());
                if (spectator != null) {
                    spectator.sendMessage("§e" + died.getName() + " §cตาย! §7รอการเกิดใหม่...");
                }
            }
        }
    }
    
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player respawned = event.getPlayer();
        
        // อัพเดตตำแหน่ง spectator ที่ติดตามผู้เล่นที่เกิดใหม่
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<UUID, UUID> entry : spectatorTargets.entrySet()) {
                    if (entry.getValue().equals(respawned.getUniqueId())) {
                        Player spectator = Bukkit.getPlayer(entry.getKey());
                        if (spectator != null) {
                            // รีเซ็ตตำแหน่งกล้อง
                            cameraOffsets.put(spectator.getUniqueId(), new Vector(0, DEFAULT_HEIGHT, -DEFAULT_DISTANCE));
                            
                            // วาร์ปไปโลกใหม่ถ้าต่างโลก
                            if (!spectator.getWorld().equals(respawned.getWorld())) {
                                spectator.teleport(respawned.getLocation());
                            }
                            
                            updateCameraPosition(spectator, respawned, true);
                            spectator.sendMessage("§a" + respawned.getName() + " §eเกิดใหม่แล้ว!");
                        }
                    }
                }
            }
        }.runTaskLater(this, 5L); // รอ 5 ticks ให้ respawn เสร็จก่อน
    }
    
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // ถ้า target เปลี่ยนโลก
        for (Map.Entry<UUID, UUID> entry : spectatorTargets.entrySet()) {
            if (entry.getValue().equals(uuid)) {
                Player spectator = Bukkit.getPlayer(entry.getKey());
                if (spectator != null && !spectator.getWorld().equals(player.getWorld())) {
                    spectator.sendMessage("§e" + player.getName() + " §7ย้ายไปโลก: §e" + player.getWorld().getName());
                    spectator.teleport(player.getLocation());
                    cameraOffsets.put(spectator.getUniqueId(), new Vector(0, DEFAULT_HEIGHT, -DEFAULT_DISTANCE));
                }
            }
        }
    }
    
    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // ถ้า target teleport ไปคนละโลก
        if (event.getFrom().getWorld() != event.getTo().getWorld()) {
            for (Map.Entry<UUID, UUID> entry : spectatorTargets.entrySet()) {
                if (entry.getValue().equals(uuid)) {
                    Player spectator = Bukkit.getPlayer(entry.getKey());
                    if (spectator != null) {
                        // รอให้ teleport เสร็จก่อนตามไป
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                if (spectator.isOnline() && player.isOnline()) {
                                    spectator.teleport(player.getLocation());
                                    cameraOffsets.put(spectator.getUniqueId(), new Vector(0, DEFAULT_HEIGHT, -DEFAULT_DISTANCE));
                                }
                            }
                        }.runTaskLater(this, 2L);
                    }
                }
            }
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // ถ้าเป็น spectator ที่ออก
        if (spectatorMode.contains(uuid)) {
            stopSpectating(player);
        }
        
        // ถ้าเป็น target ที่ออก
        for (Map.Entry<UUID, UUID> entry : new HashMap<>(spectatorTargets).entrySet()) {
            if (entry.getValue().equals(uuid)) {
                Player spectator = Bukkit.getPlayer(entry.getKey());
                if (spectator != null) {
                    spectator.sendMessage("§c" + player.getName() + " §7ออกจากเกม");
                    
                    // ถ้าเปิด auto-rotate จะสุ่มใหม่
                    if (autoRotate.getOrDefault(spectator.getUniqueId(), false)) {
                        Player newTarget = getRandomPlayer(spectator);
                        if (newTarget != null) {
                            startSpectating(spectator, newTarget);
                            spectator.sendMessage("§aเปลี่ยนไปติดตาม: §e" + newTarget.getName());
                        }
                    }
                }
            }
        }
    }
}
