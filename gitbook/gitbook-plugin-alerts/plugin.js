// script to style blockquotes for info, warning, success and danger

infoStyle = { 'background': '#eff5ff', 'border-color': '#42acf3', 'color': '#444' };
warningStyle = { 'background': '#fcf8f2', 'border-color': '#f0ad4e', 'color': '#444' };
dangerStyle = { 'background': '#fdf7f7', 'border-color': '#d9534f', 'color': '#444' };
successStyle = { 'background': '#f3f8f3', 'border-color': '#50af51', 'color': '#444' };

styleMapping = {
    '[info]': infoStyle,
    '[warning]': warningStyle,
    '[danger]': dangerStyle,
    '[success]': successStyle
}

iconMapping = {
    '[info]': '<i class="fa fa-info-circle"></i>',
    '[warning]': '<i class="fa fa-exclamation-circle"></i>',
    '[danger]': '<i class="fa fa-ban"></i>',
    '[success]': '<i class="fa fa-check-circle"></i>'
}

titleMapping = {
    '[info]': { 'color': '#42acf3' },
    '[warning]': { 'color': '#f0ad4e' },
    '[danger]': { 'color': '#d9534f' },
    '[success]': { 'color': '#50af51' }
}

require(["gitbook", "jQuery"], function(gitbook, $) {
    // Load
    gitbook.events.bind("page.change", function(e, config) {
        bqs = $('blockquote');
        bqs.each(function(index) {

            for (key in styleMapping) {
                htmlStr = $(this).html()

                if (htmlStr.indexOf(key) > 0) {
                    // remove key from text
                    htmlStr = htmlStr.replace(key, iconMapping[key]);
                    $(this).html(htmlStr);

                    // set style
                    $(this).css(styleMapping[key]);
                    $(this).find('strong').first().css(titleMapping[key]);
                }
            }

        })
    });
});
