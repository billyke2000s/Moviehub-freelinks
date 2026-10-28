package local.tv.home;

import java.util.ArrayList;
import java.util.List;

public class GuideOrderTest {

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static GuideOrder.Entry add(List<GuideOrder.Entry> list, String name, String epg, String group) {
        GuideOrder.Entry entry = new GuideOrder.Entry(name, epg, group, true);
        list.add(entry);
        return entry;
    }

    public static void main(String[] args) {
        List<GuideOrder.Entry> list = new ArrayList<>();
        GuideOrder.Entry tf1 = add(list, "TF1", "TF1.fr", "France"),
            bbc = add(list, "BBC One", "BBCOne.uk", "UK"),
            scotland = add(list, "BBC One Scotland", "BBCOneScotland.uk", "UK"),
            pop = add(list, "Pop", "Pop.uk", "UK"),
            radio = add(list, "BBC Radio 1", "BBCRadio1.uk", "UK"),
            france24 = add(list, "France 24", "France24English.fr", "UK"),
            rte = add(list, "RTE One", "RTEOne.ie", "Ireland"),
            poland = add(list, "Cartoon Network CEE Poland", "CartoonNetworkCEE.uk", "Kids"),
            unknown = add(list, "Mystery Channel", "", "VOD Italy");
        GuideOrder.plan(list);
        check(bbc.number == 101 && bbc.section.equals("Entertainment"), "BBC One is 101");
        check(scotland.number >= 801 && scotland.number <= 899, "other regions in the 800s");
        check(pop.number == 183, "other UK entertainment after 182");
        check(radio.number == 901 && radio.section.equals(GuideOrder.RADIO), "radio in the 900s");
        check(france24.number == 510 && france24.section.equals("News"), "foreign news the UK guide carries");
        check(rte.number == 1001 && rte.section.equals(GuideOrder.INTERNATIONAL), "Ireland first abroad");
        check(tf1.number > rte.number && tf1.country.equals("fr"), "France after Ireland");
        check(
            poland.country.equals("pl") && poland.section.equals(GuideOrder.INTERNATIONAL),
            "a UK channel's Polish feed"
        );
        check(unknown.country.equals("it") && unknown.number > tf1.number, "country from a group name");

        List<GuideOrder.Entry> plain = new ArrayList<>();
        GuideOrder.Entry first = add(plain, "Channel 00", "", ""),
            second = add(plain, "Channel 01", "", "");
        GuideOrder.plan(plain);
        check(first.number == 101 && second.number == 102, "lists the guide does not know keep their order");

        check(GuideOrder.country("", "FR: TF1", "").equals("fr"), "provider prefix");
        check(GuideOrder.country("", "UK | BBC One", "").equals("uk"), "provider prefix with bar");
        check(GuideOrder.name("uk").equals("United Kingdom"), "country name");
        check(GuideOrder.isRadio(new GuideOrder.Entry("Today FM", "", "Ireland", true)), "FM radio");
        check(
            !GuideOrder.isRadio(new GuideOrder.Entry("FMTV", "", "Music", true)),
            "a TV channel with FM in its name"
        );
        System.out.println("15 guide order checks passed");
    }
}
