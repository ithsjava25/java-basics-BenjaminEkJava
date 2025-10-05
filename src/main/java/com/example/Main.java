package com.example;

import com.example.api.ElpriserAPI;
import com.example.api.ElpriserAPI.Elpris;
import com.example.api.ElpriserAPI.Prisklass;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Map<String, String> input = parseArguments(args);
        if (input.containsKey("help") || args.length == 0) {
            showHelp();
            return;
}
        // Hämtar zonen som valts
        String zoneInput = (input.get("zone"));

        // Kontrollerar att zon är giltig
        if (zoneInput == null || !List.of("SE1", "SE2", "SE3", "SE4").contains(zoneInput)) {
            System.out.println("Ange giltig zon: SE1, SE2, SE3 eller SE4.");
            Scanner scanner = new Scanner(System.in);
            zoneInput = scanner.nextLine().trim();

        }

        // Konverterar zonsträng till enum som API:t använder
        Prisklass zone = Prisklass.valueOf(zoneInput);

        // Hämtar datum från --date eller använder dagens datum som standard
        LocalDate date;
        if (input.containsKey("date")) {
            try {
                date = LocalDate.parse(input.get("date"));
            } catch (DateTimeParseException e) {
                System.out.println("Ogiltigt datumformat. Använd YYYY-MM-DD.");
                return;
            }
        } else {
            date = LocalDate.now();

        }

        // Hämtar akutell tid
        ElpriserAPI api = new ElpriserAPI();
        ZonedDateTime now = ZonedDateTime.now();
        LocalDate today;

        if (input.containsKey("date")) {
            try {
                today = LocalDate.parse(input.get("date"));
                now = today.atStartOfDay(ZoneId.systemDefault());
            } catch (DateTimeParseException e) {
                System.out.println("Ogiltigt datumformat. Använd YYYY-MM-DD.");
                return;
            }
        } else {
            today = now.toLocalDate();
        }
        LocalDate tomorrow = today.plusDays(1);


        // Hämtar priser för idag och imorgon från API:t
        List<Elpris> todayPrices = api.getPriser(today, zone);
        List<Elpris> tomorrowPrices = api.getPriser(tomorrow, zone);

        // Filtrerar ut priser för kommande 24h
        List<Elpris> allPrices = new ArrayList<>();
        for (Elpris p : todayPrices) {
            if (!p.timeStart().isBefore(now)) {
                allPrices.add(p);
            }
        }
        for (Elpris p : tomorrowPrices) {
            if (p.timeStart().isBefore(now.plusHours(24))) {
                allPrices.add(p);
            }
        }

        if (allPrices.isEmpty()) {
            System.out.println("Inga priser tillgängliga.");
            return;
        }

        // Skriver ut i tidsordning
        if (input.containsKey("sorted")) {
            printSorted(allPrices);
            return;
        }


        printSummary(todayPrices, allPrices);

        // Bästa tid för laddning (2,4 eller 8h) vid --charging
        if (input.containsKey("charging")) {
            String chargingValue = input.get("charging");

            if (!List.of("2h", "4h", "8h").contains(chargingValue)) {
                System.out.println("Ogiltigt värde, använd 2h, 4h eller 8h");
                return;
            }

            try {
                int hours = Integer.parseInt(chargingValue.replace("h", ""));
                printChargingWindow(allPrices, hours);
            } catch (NumberFormatException e) {
                System.out.println("Fel vid tolkning av laddningstid. Använd 2h, 4h eller 8h");
            }
        }
    }

    // Tolkar argument som t.ex. --zone, --charging
    private static Map<String, String> parseArguments(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                String value = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
                map.put(key, value);
            }
        }
        return map;
    }

    // Skriver ut hjälptext med kommandon
    private static void showHelp() {
        System.out.println("Usage: java Main --zone <SE1|SE2|SE3|SE4> [options]");
        System.out.println("--zone SE1|SE2|SE3|SE4   (Needed)");
        System.out.println("--date YYYY-MM-DD        (Optionally, uses today if no other input)");
        System.out.println("--sorted                 (Optionally, sort by price)");
        System.out.println("--charging 2h|4h|8h      (Optionally, to find the best loading window)");
        System.out.println("--help                   (shows this help-text)");
    }

    // Visar medelpris, billigaste samt dyraste timme
    private static void printSummary(List<Elpris> today, List<Elpris> all) {
        double mean = today.stream().mapToDouble(Elpris::sekPerKWh).average().orElse(0);
        Elpris min = all.stream().min(Comparator.comparingDouble(Elpris::sekPerKWh)).orElse(null);
        Elpris max = all.stream().max(Comparator.comparingDouble(Elpris::sekPerKWh)).orElse(null);

        int meanOre = (int)Math.round(mean * 100);
        System.out.printf(" Medelpris idag: %d öre%n", meanOre);

        if (min == null) {
            System.out.println(" Ingen billig timme hittades.");
        } else {
            int minOre = (int)Math.round(Math.max(min.sekPerKWh(), 0) * 100);
            System.out.printf(" Billigaste timmen: %02d:00 (%d öre)%n", min.timeStart().getHour(), minOre);
        }

        if (max == null) {
            System.out.println(" Ingen dyr timme hittades.");
        } else {
            int maxOre = (int)Math.round(Math.max(max.sekPerKWh(), 0) * 100);
            System.out.printf(" Dyraste timmen: %02d:00 (%d öre)%n", max.timeStart().getHour(), maxOre);
        }
    }

    // Tar fram bästa laddningsperiod
    private static void printChargingWindow(List<Elpris> prices, int duration) {
        double lowestSum = Double.MAX_VALUE;
        int bestStart = -1;

        for (int i = 0; i <= prices.size() - duration; i++) {
            double sum = 0;
            for (int j = 0; j < duration; j++) {
                sum += prices.get(i + j).sekPerKWh();
            }
            if (sum < lowestSum) {
                lowestSum = sum;
                bestStart = i;
            }
        }

        if (bestStart != -1) {
            ZonedDateTime startTime = prices.get(bestStart).timeStart();
            ZonedDateTime endTime = startTime.plusHours(duration);

            startTime = startTime.withMinute(0).withSecond(0).withNano(0);
            endTime = endTime.withMinute(0).withSecond(0).withNano(0);

            int totalOre = (int)Math.round(Math.max(lowestSum, 0) * 100);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");


            System.out.printf(" Optimal laddning (%dh): %s-%s (Totalt: %d öre)%n",
                    duration,
                    startTime.format(formatter),
                    endTime.format(formatter),
                    totalOre);
        } else {
            System.out.println("Ingen laddningsperiod hittades.");
        }
    }

    // Skriver ut alla priser i tidsordning, timme för timme
    private static void printSorted(List<Elpris> prices) {
        System.out.println("Sortering av priser enligt 24h intervall:");

        Map<ZonedDateTime, Elpris> uniqueMap = new TreeMap<>();
        for (Elpris p : prices) {
            ZonedDateTime start = p.timeStart().withMinute(0).withSecond(0).withNano(0);
            uniqueMap.put(start, p);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");

        for (Map.Entry<ZonedDateTime, Elpris> entry : uniqueMap.entrySet()) {
            ZonedDateTime start = entry.getKey();
            ZonedDateTime end = start.plusHours(1);
            Elpris p = entry.getValue();
            int ore = (int)Math.round(Math.max(p.sekPerKWh(), 0) * 100);

            System.out.printf("%s-%s : %d öre%n",
                    start.format(formatter),
                    end.format(formatter),
                    ore);
        }
    }
}
















