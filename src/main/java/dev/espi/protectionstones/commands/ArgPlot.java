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
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ArgPlot implements PSCommandArg {

    @Override
    public List<String> getNames() {
        return Arrays.asList("plot");
    }

    @Override
    public boolean allowNonPlayersToExecute() {
        return false;
    }

    @Override
    public List<String> getPermissionsToExecute() {
        return Arrays.asList("protectionstones.plot");
    }

    @Override
    public HashMap<String, Boolean> getRegisteredFlags() {
        return null;
    }

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
            case "create": return handleCreate(p, args);
            case "delete": return handleDelete(p, args);
            case "add":    return handleAdd(p, args);
            case "kick":   return handleKick(p, args);
            case "list":   return handleList(p);
            default:       return PSL.msg(p, PSL.PLOT_HELP.msg());
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

        // 8 corners of the selection bounding box
        BlockVector3[] corners = corners(selMin, selMax);

        RegionManager rm = WGUtils.getRegionManagerWithPlayer(p);

        // Find smallest player-owned PS region that fully contains the selection.
        // If the player has e.g. a 16-block inside a 64-block, we pick the innermost
        // one that fits — so the selection never has to span outside a single region.
        PSRegion parent = findBestParent(p, rm, corners);
        if (parent == null) {
            return PSL.msg(p, PSL.PLOT_OUTSIDE_REGION.msg());
        }

        // Optional name — checked for uniqueness before creation
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

        // Create plot with exact Y bounds from the WE selection
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
        String nameOrId = args[2];

        RegionManager rm = WGUtils.getRegionManagerWithPlayer(p);
        List<ProtectedRegion> matches = findPlots(p, rm, nameOrId);

        if (matches.isEmpty()) {
            return PSL.msg(p, PSL.PLOT_NOT_FOUND.msg().replace("%name%", nameOrId));
        }
        if (matches.size() > 1) {
            return PSL.msg(p, PSL.PLOT_AMBIGUOUS_NAME.msg().replace("%name%", nameOrId));
        }

        ProtectedRegion plot = matches.get(0);
        String plotId = plot.getId();
        rm.removeRegion(plotId, RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
        return PSL.msg(p, PSL.PLOT_REMOVED.msg().replace("%id%", displayName(plot)));
    }

    // ─── /ps plot add <name|id> <player> ─────────────────────────────────────

    private boolean handleAdd(Player p, String[] args) {
        if (args.length < 4) return PSL.msg(p, PSL.PLOT_HELP.msg());
        String nameOrId = args[2];
        String targetName = args[3];

        if (!UUIDCache.containsName(targetName)) {
            return PSL.msg(p, PSL.PLAYER_NOT_FOUND.msg());
        }

        RegionManager rm = WGUtils.getRegionManagerWithPlayer(p);
        List<ProtectedRegion> matches = findPlots(p, rm, nameOrId);

        if (matches.isEmpty()) {
            return PSL.msg(p, PSL.PLOT_NOT_FOUND.msg().replace("%name%", nameOrId));
        }
        if (matches.size() > 1) {
            return PSL.msg(p, PSL.PLOT_AMBIGUOUS_NAME.msg().replace("%name%", nameOrId));
        }

        UUID targetUUID = UUIDCache.getUUIDFromName(targetName);
        matches.get(0).getMembers().addPlayer(targetUUID);

        return PSL.msg(p, PSL.PLOT_PLAYER_ADDED.msg()
            .replace("%player%", UUIDCache.getNameFromUUID(targetUUID))
            .replace("%plot%", displayName(matches.get(0))));
    }

    // ─── /ps plot kick <name|id> <player> ────────────────────────────────────

    private boolean handleKick(Player p, String[] args) {
        if (args.length < 4) return PSL.msg(p, PSL.PLOT_HELP.msg());
        String nameOrId = args[2];
        String targetName = args[3];

        if (!UUIDCache.containsName(targetName)) {
            return PSL.msg(p, PSL.PLAYER_NOT_FOUND.msg());
        }

        RegionManager rm = WGUtils.getRegionManagerWithPlayer(p);
        List<ProtectedRegion> matches = findPlots(p, rm, nameOrId);

        if (matches.isEmpty()) {
            return PSL.msg(p, PSL.PLOT_NOT_FOUND.msg().replace("%name%", nameOrId));
        }
        if (matches.size() > 1) {
            return PSL.msg(p, PSL.PLOT_AMBIGUOUS_NAME.msg().replace("%name%", nameOrId));
        }

        UUID targetUUID = UUIDCache.getUUIDFromName(targetName);
        ProtectedRegion plot = matches.get(0);
        plot.getMembers().removePlayer(targetUUID);
        plot.getOwners().removePlayer(targetUUID);

        return PSL.msg(p, PSL.PLOT_PLAYER_KICKED.msg()
            .replace("%player%", UUIDCache.getNameFromUUID(targetUUID))
            .replace("%plot%", displayName(plot)));
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
            String line = ChatColor.AQUA + "> " + ChatColor.WHITE + (name != null ? name : r.getId());
            line += ChatColor.GRAY + " (id: " + r.getId() + ", parent: " + parentId + ")";
            lines.add(line);
        }

        if (lines.isEmpty()) {
            return PSL.msg(p, PSL.PLOT_LIST_EMPTY.msg());
        }

        PSL.msg(p, PSL.PLOT_LIST_HEADER.msg());
        for (String line : lines) p.sendMessage(line);
        return true;
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    /**
     * Finds all plots the player can manage that match the given name or ID.
     * Returns empty list = not found, size > 1 = ambiguous name.
     */
    private List<ProtectedRegion> findPlots(Player p, RegionManager rm, String nameOrId) {
        LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(p);
        boolean isAdmin = p.hasPermission("protectionstones.admin");
        List<ProtectedRegion> nameMatches = new ArrayList<>();

        for (ProtectedRegion r : rm.getRegions().values()) {
            if (r.getFlag(FlagHandler.PS_PLOT) == null) continue;
            if (!canManagePlot(p, lp, r, rm, isAdmin)) continue;

            // Exact ID match wins immediately
            if (r.getId().equalsIgnoreCase(nameOrId)) return java.util.Collections.singletonList(r);

            String name = r.getFlag(FlagHandler.PS_NAME);
            if (name != null && name.equalsIgnoreCase(nameOrId)) {
                nameMatches.add(r);
            }
        }
        return nameMatches;
    }

    /**
     * Returns true if the player can manage a given plot:
     * – is the plot owner, OR
     * – is the owner of the plot's parent PS region, OR
     * – has protectionstones.admin
     */
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

    /**
     * Finds the smallest PS region owned by the player that fully contains
     * all 8 corners of the selection. Returns null if none qualifies.
     */
    private PSRegion findBestParent(Player p, RegionManager rm, BlockVector3[] corners) {
        PSRegion best = null;
        long bestFootprint = Long.MAX_VALUE;
        boolean isAdmin = p.hasPermission("protectionstones.admin");

        for (ProtectedRegion r : rm.getRegions().values()) {
            PSRegion psr = PSRegion.fromWGRegion(p.getWorld(), r);
            if (psr == null) continue; // not a PS region (plots, non-PS regions, etc.)
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

    /** Returns the human-readable name for a plot: custom name if set, else WG region ID. */
    private String displayName(ProtectedRegion plot) {
        String name = plot.getFlag(FlagHandler.PS_NAME);
        return name != null ? name : plot.getId();
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 2) {
            return Arrays.asList("create", "delete", "add", "kick", "list").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }
        return null;
    }
}
