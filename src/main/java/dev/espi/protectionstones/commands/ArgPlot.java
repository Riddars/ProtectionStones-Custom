/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.espi.protectionstones.commands;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.RemovalStrategy;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.espi.protectionstones.FlagHandler;
import dev.espi.protectionstones.PSL;
import dev.espi.protectionstones.PSRegion;
import dev.espi.protectionstones.ProtectionStones;
import dev.espi.protectionstones.utils.UUIDCache;
import dev.espi.protectionstones.utils.WGUtils;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class ArgPlot implements PSCommandArg {

    // ─── Public static helpers (used by ListenerClass and ArgAddRemove) ────────

    public static boolean isDenied(UUID uuid, ProtectedRegion plot) {
        String denied = plot.getFlag(FlagHandler.PS_PLOT_DENIED);
        if (denied == null || denied.isEmpty()) return false;
        for (String part : denied.split(",")) {
            if (part.trim().equalsIgnoreCase(uuid.toString())) return true;
        }
        return false;
    }

    public static void addDenied(ProtectedRegion plot, UUID uuid) {
        if (isDenied(uuid, plot)) return;
        String current = plot.getFlag(FlagHandler.PS_PLOT_DENIED);
        String newVal = (current == null || current.isEmpty()) ? uuid.toString() : current + "," + uuid;
        plot.setFlag(FlagHandler.PS_PLOT_DENIED, newVal);
    }

    public static void removeDenied(ProtectedRegion plot, UUID uuid) {
        String current = plot.getFlag(FlagHandler.PS_PLOT_DENIED);
        if (current == null || current.isEmpty()) return;
        String newVal = Arrays.stream(current.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.equalsIgnoreCase(uuid.toString()))
                .collect(Collectors.joining(","));
        plot.setFlag(FlagHandler.PS_PLOT_DENIED, newVal);
    }

    // ─── Command interface ─────────────────────────────────────────────────────

    @Override
    public List<String> getNames() { return Arrays.asList("plot"); }

    @Override
    public boolean allowNonPlayersToExecute() { return false; }

    @Override
    public List<String> getPermissionsToExecute() { return Arrays.asList("protectionstones.plot"); }

    @Override
    public HashMap<String, Boolean> getRegisteredFlags() { return null; }

    @Override
    public boolean executeArgument(CommandSender s, String[] args, HashMap<String, String> flags) {
        Player p = (Player) s;
        if (!p.hasPermission("protectionstones.plot")) {
            return PSL.msg(p, PSL.NO_PERMISSION_PLOT.msg());
        }
        if (args.length < 2) {
            return PSL.msg(p, PSL.PLOT_HELP.msg());
        }
        switch (args[1].toLowerCase()) {
            case "create":  return handleCreate(p, args);
            case "delete":  return handleDelete(p, args);
            case "add":     return handleAdd(p, args);
            case "kick":    return handleKick(p, args);
            case "kickall": return handleKickAll(p, args);
            case "list":    return handleList(p);
            default:        return PSL.msg(p, PSL.PLOT_HELP.msg());
        }
    }

    // ─── /ps plot create [name] ───────────────────────────────────────────────

    private boolean handleCreate(Player p, String[] args) {
        Region selection;
        try {
            LocalSession session = WorldEdit.getInstance().getSessionManager()
                    .get(BukkitAdapter.adapt(p));
            selection = session.getSelection(BukkitAdapter.adapt(p.getWorld()));
        } catch (IncompleteRegionException e) {
            return PSL.msg(p, PSL.PLOT_NO_SELECTION.msg());
        }

        BlockVector3 selMin = selection.getMinimumPoint();
        BlockVector3 selMax = selection.getMaximumPoint();
        BlockVector3[] corners = corners(selMin, selMax);

        RegionManager rm = WGUtils.getRegionManagerWithPlayer(p);

        PSRegion parent = findBestParent(p, rm, corners);
        if (parent == null) {
            return PSL.msg(p, PSL.PLOT_OUTSIDE_REGION.msg());
        }

        // Reject if selection overlaps an existing plot in the same parent
        if (overlapsExistingPlot(rm, parent.getId(), selMin, selMax)) {
            return PSL.msg(p, PSL.PLOT_OVERLAP.msg());
        }

        // Optional name — checked for uniqueness per player per world
        String plotName = args.length >= 3 ? args[2] : null;
        if (plotName != null) {
            LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(p);
            for (ProtectedRegion r : rm.getRegions().values()) {
                if (r.getFlag(FlagHandler.PS_PLOT) == null) continue;
                if (!r.isOwner(lp) && !p.hasPermission("protectionstones.admin")) continue;
                String existingName = r.getFlag(FlagHandler.PS_NAME);
                if (plotName.equalsIgnoreCase(existingName)) {
                    return PSL.msg(p, PSL.PLOT_NAME_TAKEN.msg().replace("%name%", plotName));
                }
            }
        }

        // Economy check
        double cost = getCreateCost();
        Economy eco = ProtectionStones.getInstance().getVaultEconomy();
        if (cost > 0 && eco != null) {
            if (!eco.has(p, cost)) {
                return PSL.msg(p, PSL.NOT_ENOUGH_MONEY.msg().replace("%price%", String.valueOf((long) cost)));
            }
        }

        String plotId = generatePlotId(rm);
        ProtectedCuboidRegion plotWG = new ProtectedCuboidRegion(
                plotId,
                BlockVector3.at(selMin.x(), selMin.y(), selMin.z()),
                BlockVector3.at(selMax.x(), selMax.y(), selMax.z())
        );

        ProtectedRegion parentWG = parent.getWGRegion();
        plotWG.getOwners().addPlayer(p.getUniqueId());
        plotWG.setPriority(parentWG.getPriority() + 10);
        plotWG.setFlag(FlagHandler.PS_PLOT, parent.getId());

        try {
            plotWG.setParent(parentWG);
        } catch (ProtectedRegion.CircularInheritanceException e) {
            return PSL.msg(p, ChatColor.RED + "Could not set parent region (circular inheritance).");
        }

        if (plotName != null) {
            plotWG.setFlag(FlagHandler.PS_NAME, plotName);
        }

        rm.addRegion(plotWG);

        if (cost > 0 && eco != null) {
            eco.withdrawPlayer(p, cost);
            PSL.msg(p, PSL.PAID_MONEY.msg().replace("%price%", String.valueOf((long) cost)));
        }

        return PSL.msg(p, PSL.PLOT_CREATED.msg()
                .replace("%id%", plotName != null ? plotName : plotId)
                .replace("%parent%", parent.getId()));
    }

    // ─── /ps plot delete <name|id> ────────────────────────────────────────────

    private boolean handleDelete(Player p, String[] args) {
        if (args.length < 3) return PSL.msg(p, PSL.PLOT_HELP.msg());
        RegionManager rm = WGUtils.getRegionManagerWithPlayer(p);
        List<ProtectedRegion> matches = findPlots(p, rm, args[2]);
        if (matches.isEmpty()) return PSL.msg(p, PSL.PLOT_NOT_FOUND.msg().replace("%name%", args[2]));
        if (matches.size() > 1) return PSL.msg(p, PSL.PLOT_AMBIGUOUS_NAME.msg().replace("%name%", args[2]));
        ProtectedRegion plot = matches.get(0);
        rm.removeRegion(plot.getId(), RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
        return PSL.msg(p, PSL.PLOT_REMOVED.msg().replace("%id%", displayName(plot)));
    }

    // ─── /ps plot add <name|id> <player> ─────────────────────────────────────

    private boolean handleAdd(Player p, String[] args) {
        if (args.length < 4) return PSL.msg(p, PSL.PLOT_HELP.msg());
        if (!UUIDCache.containsName(args[3])) return PSL.msg(p, PSL.PLAYER_NOT_FOUND.msg());

        RegionManager rm = WGUtils.getRegionManagerWithPlayer(p);
        List<ProtectedRegion> matches = findPlots(p, rm, args[2]);
        if (matches.isEmpty()) return PSL.msg(p, PSL.PLOT_NOT_FOUND.msg().replace("%name%", args[2]));
        if (matches.size() > 1) return PSL.msg(p, PSL.PLOT_AMBIGUOUS_NAME.msg().replace("%name%", args[2]));

        UUID targetUUID = UUIDCache.getUUIDFromName(args[3]);
        ProtectedRegion plot = matches.get(0);

        // Remove from denied list (restore access) and add to members
        removeDenied(plot, targetUUID);
        plot.getMembers().addPlayer(targetUUID);

        return PSL.msg(p, PSL.PLOT_PLAYER_ADDED.msg()
                .replace("%player%", UUIDCache.getNameFromUUID(targetUUID))
                .replace("%plot%", displayName(plot)));
    }

    // ─── /ps plot kick <name|id> <player> ────────────────────────────────────

    private boolean handleKick(Player p, String[] args) {
        if (args.length < 4) return PSL.msg(p, PSL.PLOT_HELP.msg());
        if (!UUIDCache.containsName(args[3])) return PSL.msg(p, PSL.PLAYER_NOT_FOUND.msg());

        RegionManager rm = WGUtils.getRegionManagerWithPlayer(p);
        List<ProtectedRegion> matches = findPlots(p, rm, args[2]);
        if (matches.isEmpty()) return PSL.msg(p, PSL.PLOT_NOT_FOUND.msg().replace("%name%", args[2]));
        if (matches.size() > 1) return PSL.msg(p, PSL.PLOT_AMBIGUOUS_NAME.msg().replace("%name%", args[2]));

        UUID targetUUID = UUIDCache.getUUIDFromName(args[3]);
        ProtectedRegion plot = matches.get(0);

        // Parent region owner always retains access — cannot be denied
        String parentId = plot.getFlag(FlagHandler.PS_PLOT);
        ProtectedRegion parent = parentId != null ? rm.getRegion(parentId) : null;
        if (parent != null && parent.getOwners().getUniqueIds().contains(targetUUID)) {
            return PSL.msg(p, PSL.PLOT_CANNOT_KICK_PARENT_OWNER.msg());
        }

        // Remove from members/owners and add to explicit deny list
        plot.getMembers().removePlayer(targetUUID);
        plot.getOwners().removePlayer(targetUUID);
        addDenied(plot, targetUUID);

        return PSL.msg(p, PSL.PLOT_PLAYER_KICKED.msg()
                .replace("%player%", UUIDCache.getNameFromUUID(targetUUID))
                .replace("%plot%", displayName(plot)));
    }

    // ─── /ps plot kickall <player> ────────────────────────────────────────────

    private boolean handleKickAll(Player p, String[] args) {
        if (args.length < 3) return PSL.msg(p, PSL.PLOT_HELP.msg());
        if (!UUIDCache.containsName(args[2])) return PSL.msg(p, PSL.PLAYER_NOT_FOUND.msg());

        UUID targetUUID = UUIDCache.getUUIDFromName(args[2]);
        RegionManager rm = WGUtils.getRegionManagerWithPlayer(p);
        LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(p);
        boolean isAdmin = p.hasPermission("protectionstones.admin");

        int count = 0;
        for (ProtectedRegion r : rm.getRegions().values()) {
            if (r.getFlag(FlagHandler.PS_PLOT) == null) continue;
            if (!canManagePlot(p, lp, r, rm, isAdmin)) continue;

            // Skip plots where target is parent region owner
            String parentId = r.getFlag(FlagHandler.PS_PLOT);
            ProtectedRegion parent = parentId != null ? rm.getRegion(parentId) : null;
            if (parent != null && parent.getOwners().getUniqueIds().contains(targetUUID)) continue;

            r.getMembers().removePlayer(targetUUID);
            r.getOwners().removePlayer(targetUUID);
            addDenied(r, targetUUID);
            count++;
        }

        if (count == 0) {
            return PSL.msg(p, PSL.PLOT_NOT_FOUND.msg().replace("%name%", args[2]));
        }

        return PSL.msg(p, PSL.PLOT_KICKALL.msg()
                .replace("%player%", UUIDCache.getNameFromUUID(targetUUID))
                .replace("%count%", String.valueOf(count)));
    }

    // ─── /ps plot list ────────────────────────────────────────────────────────

    private boolean handleList(Player p) {
        RegionManager rm = WGUtils.getRegionManagerWithPlayer(p);
        LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(p);
        boolean isAdmin = p.hasPermission("protectionstones.admin");

        List<String> lines = new ArrayList<>();
        for (ProtectedRegion r : rm.getRegions().values()) {
            if (r.getFlag(FlagHandler.PS_PLOT) == null) continue;
            if (!r.isOwner(lp) && !canManagePlot(p, lp, r, rm, isAdmin)) continue;

            String name = r.getFlag(FlagHandler.PS_NAME);
            String parentId = r.getFlag(FlagHandler.PS_PLOT);
            ProtectedRegion parent = rm.getRegion(parentId);

            // Effective access: (parent members ∪ plot members ∪ plot owners) − denied
            Set<UUID> denied = getDeniedSet(r);
            Set<UUID> direct = new HashSet<>(r.getMembers().getUniqueIds());
            direct.addAll(r.getOwners().getUniqueIds());
            Set<UUID> fromParent = parent != null ? new HashSet<>(parent.getMembers().getUniqueIds()) : new HashSet<>();

            Set<UUID> effective = new HashSet<>();
            effective.addAll(direct);
            effective.addAll(fromParent);
            effective.removeAll(denied);
            effective.remove(p.getUniqueId()); // don't list self

            List<String> accessList = new ArrayList<>();
            for (UUID uuid : effective) {
                String pName = UUIDCache.getNameFromUUID(uuid);
                if (pName == null) continue;
                boolean viaParent = fromParent.contains(uuid) && !direct.contains(uuid);
                accessList.add(viaParent
                        ? ChatColor.YELLOW + pName + ChatColor.GRAY + "(via parent)"
                        : ChatColor.WHITE + pName);
            }

            BlockVector3 pMin = r.getMinimumPoint();
            BlockVector3 pMax = r.getMaximumPoint();
            String coords = ChatColor.DARK_GRAY + "("
                    + pMin.x() + "," + pMin.y() + "," + pMin.z()
                    + ChatColor.DARK_GRAY + ")→("
                    + pMax.x() + "," + pMax.y() + "," + pMax.z()
                    + ChatColor.DARK_GRAY + ")";

            String displayStr = name != null ? name : r.getId();
            String line = ChatColor.AQUA + "● " + ChatColor.WHITE + displayStr
                    + ChatColor.GRAY + " [" + parentId + "] " + coords
                    + ChatColor.GRAY + " | Access: "
                    + (accessList.isEmpty() ? ChatColor.GRAY + "none" : String.join(ChatColor.GRAY + ", ", accessList));
            lines.add(line);
        }

        if (lines.isEmpty()) return PSL.msg(p, PSL.PLOT_LIST_EMPTY.msg());

        PSL.msg(p, PSL.PLOT_LIST_HEADER.msg());
        for (String line : lines) p.sendMessage(line);
        return true;
    }

    // ─── Tab completion ───────────────────────────────────────────────────────

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!(sender instanceof Player)) return null;
        Player p = (Player) sender;

        if (args.length == 2) {
            return Arrays.asList("create", "delete", "add", "kick", "kickall", "list").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 3) {
            switch (args[1].toLowerCase()) {
                case "delete":
                case "add":
                case "kick": {
                    RegionManager rm = WGUtils.getRegionManagerWithPlayer(p);
                    if (rm == null) return null;
                    LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(p);
                    boolean isAdmin = p.hasPermission("protectionstones.admin");
                    List<String> names = new ArrayList<>();
                    for (ProtectedRegion r : rm.getRegions().values()) {
                        if (r.getFlag(FlagHandler.PS_PLOT) == null) continue;
                        if (!canManagePlot(p, lp, r, rm, isAdmin)) continue;
                        String plotName = r.getFlag(FlagHandler.PS_NAME);
                        names.add(plotName != null ? plotName : r.getId());
                    }
                    return names.stream()
                            .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
                }
                case "kickall":
                    return Bukkit.getOnlinePlayers().stream()
                            .filter(pl -> p.canSee(pl))
                            .map(Player::getName)
                            .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                            .collect(Collectors.toList());
            }
        }

        if (args.length == 4) {
            switch (args[1].toLowerCase()) {
                case "add":
                case "kick":
                    return Bukkit.getOnlinePlayers().stream()
                            .filter(pl -> p.canSee(pl))
                            .map(Player::getName)
                            .filter(n -> n.toLowerCase().startsWith(args[3].toLowerCase()))
                            .collect(Collectors.toList());
            }
        }

        return null;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private List<ProtectedRegion> findPlots(Player p, RegionManager rm, String nameOrId) {
        LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(p);
        boolean isAdmin = p.hasPermission("protectionstones.admin");
        List<ProtectedRegion> nameMatches = new ArrayList<>();

        for (ProtectedRegion r : rm.getRegions().values()) {
            if (r.getFlag(FlagHandler.PS_PLOT) == null) continue;
            if (!canManagePlot(p, lp, r, rm, isAdmin)) continue;
            if (r.getId().equalsIgnoreCase(nameOrId)) return Collections.singletonList(r);
            String name = r.getFlag(FlagHandler.PS_NAME);
            if (name != null && name.equalsIgnoreCase(nameOrId)) nameMatches.add(r);
        }
        return nameMatches;
    }

    private boolean canManagePlot(Player p, LocalPlayer lp, ProtectedRegion plot,
                                   RegionManager rm, boolean isAdmin) {
        if (isAdmin) return true;
        if (plot.isOwner(lp)) return true;
        String parentId = plot.getFlag(FlagHandler.PS_PLOT);
        if (parentId != null) {
            ProtectedRegion parent = rm.getRegion(parentId);
            if (parent != null && parent.isOwner(lp)) return true;
        }
        return false;
    }

    private PSRegion findBestParent(Player p, RegionManager rm, BlockVector3[] corners) {
        PSRegion best = null;
        long bestFootprint = Long.MAX_VALUE;
        boolean isAdmin = p.hasPermission("protectionstones.admin");

        for (ProtectedRegion r : rm.getRegions().values()) {
            PSRegion psr = PSRegion.fromWGRegion(p.getWorld(), r);
            if (psr == null) continue;
            if (!psr.isOwner(p.getUniqueId()) && !isAdmin) continue;

            boolean containsAll = true;
            for (BlockVector3 corner : corners) {
                if (!r.contains(corner)) { containsAll = false; break; }
            }
            if (!containsAll) continue;

            long footprint = regionFootprint(r);
            if (footprint < bestFootprint) {
                bestFootprint = footprint;
                best = psr;
            }
        }
        return best;
    }

    private boolean overlapsExistingPlot(RegionManager rm, String parentId,
                                          BlockVector3 selMin, BlockVector3 selMax) {
        for (ProtectedRegion r : rm.getRegions().values()) {
            if (!parentId.equals(r.getFlag(FlagHandler.PS_PLOT))) continue;
            BlockVector3 rMin = r.getMinimumPoint();
            BlockVector3 rMax = r.getMaximumPoint();
            if (selMax.x() >= rMin.x() && selMin.x() <= rMax.x() &&
                selMax.y() >= rMin.y() && selMin.y() <= rMax.y() &&
                selMax.z() >= rMin.z() && selMin.z() <= rMax.z()) {
                return true;
            }
        }
        return false;
    }

    private Set<UUID> getDeniedSet(ProtectedRegion plot) {
        Set<UUID> denied = new HashSet<>();
        String deniedStr = plot.getFlag(FlagHandler.PS_PLOT_DENIED);
        if (deniedStr == null || deniedStr.isEmpty()) return denied;
        for (String s : deniedStr.split(",")) {
            try { denied.add(UUID.fromString(s.trim())); } catch (Exception ignored) {}
        }
        return denied;
    }

    private BlockVector3[] corners(BlockVector3 min, BlockVector3 max) {
        return new BlockVector3[]{
                BlockVector3.at(min.x(), min.y(), min.z()),
                BlockVector3.at(max.x(), min.y(), min.z()),
                BlockVector3.at(min.x(), min.y(), max.z()),
                BlockVector3.at(max.x(), min.y(), max.z()),
                BlockVector3.at(min.x(), max.y(), min.z()),
                BlockVector3.at(max.x(), max.y(), min.z()),
                BlockVector3.at(min.x(), max.y(), max.z()),
                BlockVector3.at(max.x(), max.y(), max.z())
        };
    }

    private long regionFootprint(ProtectedRegion r) {
        BlockVector3 min = r.getMinimumPoint();
        BlockVector3 max = r.getMaximumPoint();
        return (long)(max.x() - min.x() + 1) * (max.z() - min.z() + 1);
    }

    private String generatePlotId(RegionManager rm) {
        String id;
        do { id = "psplot_" + Long.toHexString(System.nanoTime()); }
        while (rm.getRegion(id) != null);
        return id;
    }

    private double getCreateCost() {
        Double cost = ProtectionStones.getInstance().getConfigOptions().plotCreateCost;
        return cost != null ? cost : 1500.0;
    }

    private String displayName(ProtectedRegion plot) {
        String name = plot.getFlag(FlagHandler.PS_NAME);
        return name != null ? name : plot.getId();
    }
}
