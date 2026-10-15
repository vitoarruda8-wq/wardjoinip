package fr.wardluik.joinip;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

/** Isole l'API LuckPerms : cette classe n'est chargee que si LuckPerms est present. */
final class LuckPermsHook {

    private LuckPermsHook() {}

    static RankInfo lookup(Player player) {
        LuckPerms luckPerms = LuckPermsProvider.get();
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) return new RankInfo(null, null);
        String prefix = user.getCachedData().getMetaData().getPrefix();
        return new RankInfo(prefix, user.getPrimaryGroup());
    }
}
